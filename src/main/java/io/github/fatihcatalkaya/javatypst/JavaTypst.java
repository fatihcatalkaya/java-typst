package io.github.fatihcatalkaya.javatypst;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JavaTypst {

    // ── WASM instance (initialized once, under LOCK) ─────────────────────────

    private static volatile Instance instance;
    private static volatile ExportFunction allocFn;
    private static volatile ExportFunction deallocFn;
    private static volatile ExportFunction renderFn;
    private static volatile ExportFunction lastErrPtrFn;
    private static volatile ExportFunction lastErrLenFn;

    private static final Object LOCK = new Object();

    // ── Package resolution configuration ─────────────────────────────────────

    private static volatile TypstPackageResolver packageResolver = new HttpPackageResolver();
    private static volatile Path packageCacheDir = defaultCacheDir();
    private static volatile PackageDiskCache diskCache;

    // Set for the duration of a render(String, Map) call (URL-keyed, map-only mode).
    // null means: use HTTP fallback via diskCache + packageResolver.
    private static Map<String, byte[]> currentPackageUrlMap = null;

    // Holds fetched bytes between the two-call host protocol (size-query then data-write).
    // Safe without synchronization because all rendering is serialized under LOCK.
    private static final Map<String, byte[]> pendingFetches = new HashMap<>();

    // ── Public configuration API ──────────────────────────────────────────────

    /**
     * Replaces the package resolver used for HTTP downloads.
     * Must be called before the first {@link #render} if you also call
     * {@link #setPackageCacheDirectory}; otherwise may be called at any time.
     */
    public static void setPackageResolver(TypstPackageResolver resolver) {
        if (resolver == null) throw new NullPointerException("resolver");
        synchronized (LOCK) {
            packageResolver = resolver;
        }
    }

    /**
     * Overrides the disk cache directory (default: {@code $XDG_CACHE_HOME/java-typst/packages}).
     * Must be called before the first {@link #render} call.
     */
    public static void setPackageCacheDirectory(Path dir) {
        if (dir == null) throw new NullPointerException("dir");
        synchronized (LOCK) {
            if (instance != null) {
                throw new IllegalStateException("setPackageCacheDirectory must be called before the first render");
            }
            packageCacheDir = dir;
        }
    }

    // ── Public render API ─────────────────────────────────────────────────────

    /**
     * Renders Typst markup to a PDF document.
     *
     * @param content Typst source (must not be null)
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails
     */
    public static byte[] render(String content) {
        if (content == null) throw new NullPointerException("content");
        synchronized (LOCK) {
            return renderUnderLock(content);
        }
    }

    /**
     * Renders Typst markup with pre-fetched package archives (air-gapped / custom-cache mode).
     * Package imports that are not present in {@code packages} cause a {@link TypstRenderException};
     * no HTTP requests are made.
     *
     * @param content  Typst source (must not be null)
     * @param packages map of {@code "@namespace/name:version"} → raw {@code .tar.gz} bytes
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails or a required package is absent from the map
     */
    public static byte[] render(String content, Map<String, byte[]> packages) {
        if (content == null) throw new NullPointerException("content");
        if (packages == null) throw new NullPointerException("packages");
        synchronized (LOCK) {
            Map<String, byte[]> urlMap = new HashMap<>();
            for (Map.Entry<String, byte[]> e : packages.entrySet()) {
                urlMap.put(specToUrl(e.getKey()), e.getValue());
            }
            currentPackageUrlMap = urlMap;
            try {
                return renderUnderLock(content);
            } finally {
                currentPackageUrlMap = null;
            }
        }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    // Caller must hold LOCK.
    private static void ensureInitialized() {
        if (instance != null) return;
        try (InputStream stream =
                JavaTypst.class.getResourceAsStream("/io/github/fatihcatalkaya/javatypst/java_typst.wasm")) {
            if (stream == null) {
                throw new RuntimeException("java_typst.wasm not found on classpath");
            }

            diskCache = new PackageDiskCache(packageCacheDir);

            var wasi = WasiPreview1.builder()
                    .withOptions(WasiOptions.builder().build())
                    .build();

            var fetchFn = new HostFunction(
                    "java_typst_host",
                    "host_fetch_url",
                    FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32)),
                    (Instance inst, long... args) -> {
                        int urlPtr = (int) args[0], urlLen = (int) args[1];
                        int outBuf = (int) args[2], outCap = (int) args[3];
                        Memory mem = inst.memory();
                        String url = mem.readString(urlPtr, urlLen);
                        if (outBuf == 0) {
                            try {
                                byte[] bytes = fetchPackage(url);
                                pendingFetches.put(url, bytes);
                                return new long[] {bytes.length};
                            } catch (Exception ex) {
                                return new long[] {-1};
                            }
                        }
                        byte[] bytes = pendingFetches.remove(url);
                        if (bytes == null) return new long[] {-1};
                        int len = Math.min(bytes.length, outCap);
                        mem.write(outBuf, Arrays.copyOf(bytes, len));
                        return new long[] {len};
                    });

            var store = new Store().addFunction(wasi.toHostFunctions()).addFunction(fetchFn);

            Instance newInstance = store.instantiate("java-typst", Parser.parse(stream));
            allocFn = newInstance.export("alloc");
            deallocFn = newInstance.export("dealloc");
            renderFn = newInstance.export("render");
            lastErrPtrFn = newInstance.export("last_error_ptr");
            lastErrLenFn = newInstance.export("last_error_len");
            instance = newInstance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load java_typst.wasm", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static byte[] renderUnderLock(String content) {
        ensureInitialized();
        Memory memory = instance.memory();

        byte[] inputBytes = content.getBytes(StandardCharsets.UTF_8);
        int inLen = inputBytes.length;
        int inPtr = (int) allocFn.apply(inLen)[0];
        int outLenPtr = (int) allocFn.apply(4)[0];
        try {
            memory.write(inPtr, inputBytes);
            int outPtr = (int) renderFn.apply(inPtr, inLen, outLenPtr)[0];
            if (outPtr == 0) {
                int errPtr = (int) lastErrPtrFn.apply()[0];
                int errLen = (int) lastErrLenFn.apply()[0];
                String errorMsg = memory.readString(errPtr, errLen);
                throw new TypstRenderException(errorMsg);
            }
            int outLen = memory.readInt(outLenPtr);
            byte[] pdfBytes = memory.readBytes(outPtr, outLen);
            deallocFn.apply(outPtr, outLen);
            return pdfBytes;
        } finally {
            deallocFn.apply(outLenPtr, 4);
            deallocFn.apply(inPtr, inLen);
            pendingFetches.clear();
        }
    }

    private static byte[] fetchPackage(String url) throws TypstPackageNotFoundException {
        if (currentPackageUrlMap != null) {
            byte[] bytes = currentPackageUrlMap.get(url);
            if (bytes == null) {
                String[] p = parsePackageUrl(url);
                throw new TypstPackageNotFoundException(p[0], p[1], p[2]);
            }
            return bytes;
        }
        String[] p = parsePackageUrl(url);
        return diskCache.get(p[0], p[1], p[2], packageResolver);
    }

    /** Parses "https://packages.typst.org/preview/cetz-0.3.2.tar.gz" → ["preview","cetz","0.3.2"] */
    private static String[] parsePackageUrl(String url) {
        String path = url.substring("https://packages.typst.org/".length());
        int slash = path.indexOf('/');
        String namespace = path.substring(0, slash);
        String filename = path.substring(slash + 1, path.length() - ".tar.gz".length());
        int lastHyphen = filename.lastIndexOf('-');
        String name = filename.substring(0, lastHyphen);
        String version = filename.substring(lastHyphen + 1);
        return new String[] {namespace, name, version};
    }

    /** Converts "@preview/cetz:0.3.2" → "https://packages.typst.org/preview/cetz-0.3.2.tar.gz" */
    private static String specToUrl(String spec) {
        String s = spec.startsWith("@") ? spec.substring(1) : spec;
        int colon = s.lastIndexOf(':');
        String version = s.substring(colon + 1);
        String nsAndName = s.substring(0, colon);
        int slash = nsAndName.indexOf('/');
        String namespace = nsAndName.substring(0, slash);
        String name = nsAndName.substring(slash + 1);
        return "https://packages.typst.org/" + namespace + "/" + name + "-" + version + ".tar.gz";
    }

    private static Path defaultCacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isEmpty()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("java-typst/packages");
    }

    private JavaTypst() {}
}
