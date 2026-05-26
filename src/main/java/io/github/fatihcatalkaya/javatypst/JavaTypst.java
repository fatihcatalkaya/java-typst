package io.github.fatihcatalkaya.javatypst;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.ByteArrayOutputStream;
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

    private static volatile boolean aotEnabled = false;

    // ── Tag values for the TLV options blob (must mirror lib.rs) ─────────────

    private static final int TAG_INPUTS = 1;
    private static final int TAG_FONTS = 2;

    // ── Package resolution configuration ─────────────────────────────────────

    private static volatile TypstPackageResolver packageResolver = new HttpPackageResolver();
    private static volatile Path packageCacheDir = defaultCacheDir();
    private static volatile PackageDiskCache diskCache;

    // Set for the duration of a render whose options carry an air-gapped package map; null means
    // "use the HTTP fallback via diskCache + packageResolver."
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

    /**
     * Switches to the AOT-compiled machine. Must be called before the first {@link #render}.
     */
    public static void enableAot() {
        synchronized (LOCK) {
            if (instance != null) {
                throw new IllegalStateException("enableAot must be called before the first render");
            }
            aotEnabled = true;
        }
    }

    /** Tears down the current instance so it will be re-initialized on the next render. For testing only. */
    static void reset() {
        synchronized (LOCK) {
            instance = null;
            allocFn = null;
            deallocFn = null;
            renderFn = null;
            lastErrPtrFn = null;
            lastErrLenFn = null;
            aotEnabled = false;
            diskCache = null;
        }
    }

    // ── Public render API ─────────────────────────────────────────────────────

    /**
     * Renders Typst markup to a PDF with default options (no {@code sys.inputs}, no custom
     * fonts, HTTP package fallback). Shorthand for
     * {@code render(content, RenderOptions.DEFAULT)}.
     *
     * @param content Typst source (must not be null)
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails
     */
    public static byte[] render(String content) {
        return render(content, RenderOptions.DEFAULT);
    }

    /**
     * Renders Typst markup to a PDF with the given options.
     *
     * <p>This is the single render entry point: every optional feature — {@code sys.inputs},
     * custom fonts, air-gapped package maps, and anything we add in the future — flows in
     * through {@link RenderOptions}. There is no cartesian product of overloads.
     *
     * @param content Typst source (must not be null)
     * @param options render options (must not be null; use {@link RenderOptions#DEFAULT} for none)
     * @return PDF as a byte array
     * @throws TypstRenderException if compilation fails or a required package is absent from an
     *                              air-gapped package map
     */
    public static byte[] render(String content, RenderOptions options) {
        if (content == null) throw new NullPointerException("content");
        if (options == null) throw new NullPointerException("options");
        byte[] optsBlob = encodeOptions(options);
        Map<String, byte[]> airGappedPackages = options.packagesOrNull();
        synchronized (LOCK) {
            Map<String, byte[]> urlMap = null;
            if (airGappedPackages != null) {
                urlMap = new HashMap<>();
                for (Map.Entry<String, byte[]> e : airGappedPackages.entrySet()) {
                    urlMap.put(specToUrl(e.getKey()), e.getValue());
                }
            }
            currentPackageUrlMap = urlMap;
            try {
                return renderUnderLock(content, optsBlob);
            } finally {
                currentPackageUrlMap = null;
            }
        }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    // Caller must hold LOCK.
    private static void ensureInitialized() {
        if (instance != null) return;
        try {
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

            Instance newInstance;
            if (aotEnabled) {
                var imports = ImportValues.builder()
                        .addFunction(wasi.toHostFunctions())
                        .addFunction(fetchFn)
                        .build();
                newInstance = Instance.builder(JavaTypstModule.load())
                        .withMachineFactory(JavaTypstModule::create)
                        .withImportValues(imports)
                        .build();
            } else {
                try (InputStream stream =
                        JavaTypst.class.getResourceAsStream("/io/github/fatihcatalkaya/javatypst/java_typst.wasm")) {
                    if (stream == null) throw new RuntimeException("java_typst.wasm not found on classpath");
                    var store = new Store().addFunction(wasi.toHostFunctions()).addFunction(fetchFn);
                    newInstance = store.instantiate("java-typst", Parser.parse(stream));
                }
            }

            allocFn = newInstance.export("alloc");
            deallocFn = newInstance.export("dealloc");
            renderFn = newInstance.export("render");
            lastErrPtrFn = newInstance.export("last_error_ptr");
            lastErrLenFn = newInstance.export("last_error_len");
            instance = newInstance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JavaTypst", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static byte[] renderUnderLock(String content, byte[] optsBlob) {
        ensureInitialized();
        Memory memory = instance.memory();

        byte[] srcBytes = content.getBytes(StandardCharsets.UTF_8);
        int srcLen = srcBytes.length;
        int optsLen = optsBlob.length;
        int srcPtr = (int) allocFn.apply(srcLen)[0];
        int optsPtr = (int) allocFn.apply(optsLen)[0];
        int outLenPtr = (int) allocFn.apply(4)[0];
        try {
            memory.write(srcPtr, srcBytes);
            memory.write(optsPtr, optsBlob);
            int outPtr = (int) renderFn.apply(srcPtr, srcLen, optsPtr, optsLen, outLenPtr)[0];
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
            deallocFn.apply(optsPtr, optsLen);
            deallocFn.apply(srcPtr, srcLen);
            pendingFetches.clear();
        }
    }

    /**
     * Encodes a {@link RenderOptions} into the TLV blob consumed by the {@code render} WASM
     * export. Layout:
     *
     * <pre>
     * field_count:u32   then, repeated field_count times:
     *   tag:u32   1 = inputs, 2 = fonts
     *   len:u32   length of the payload in bytes
     *   payload:bytes   tag-specific (see encodeInputs / encodeFonts)
     * </pre>
     *
     * Empty fields are omitted entirely — an empty inputs map or an empty fonts list does not
     * contribute a TLV record, so a default {@link RenderOptions} produces just {@code [0,0,0,0]}.
     */
    private static byte[] encodeOptions(RenderOptions options) {
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        int fieldCount = 0;

        if (!options.inputs().isEmpty()) {
            byte[] payload = encodeInputs(options.inputs());
            writeLittleEndianInt(fields, TAG_INPUTS);
            writeLittleEndianInt(fields, payload.length);
            fields.writeBytes(payload);
            fieldCount++;
        }
        if (!options.fonts().isEmpty()) {
            byte[] payload = encodeFonts(options.fonts());
            writeLittleEndianInt(fields, TAG_FONTS);
            writeLittleEndianInt(fields, payload.length);
            fields.writeBytes(payload);
            fieldCount++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, fieldCount);
        out.writeBytes(fields.toByteArray());
        return out.toByteArray();
    }

    /**
     * Wire format for {@code sys.inputs}: u32 entry count, then for each entry a length-prefixed
     * UTF-8 key followed by a length-prefixed UTF-8 value. All integers little-endian.
     */
    private static byte[] encodeInputs(Map<String, String> inputs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, inputs.size());
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null) throw new NullPointerException("input key must not be null");
            if (value == null) throw new NullPointerException("input value must not be null (key: " + key + ")");
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
            writeLittleEndianInt(out, keyBytes.length);
            out.writeBytes(keyBytes);
            writeLittleEndianInt(out, valueBytes.length);
            out.writeBytes(valueBytes);
        }
        return out.toByteArray();
    }

    /**
     * Wire format for custom fonts: u32 entry count, then for each entry a u32 length followed
     * by that many raw font bytes. All integers little-endian.
     */
    private static byte[] encodeFonts(List<byte[]> fonts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianInt(out, fonts.size());
        for (byte[] font : fonts) {
            if (font == null) throw new NullPointerException("font bytes must not be null");
            writeLittleEndianInt(out, font.length);
            out.writeBytes(font);
        }
        return out.toByteArray();
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
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
