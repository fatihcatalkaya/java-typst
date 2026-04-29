package io.github.fatihcatalkaya.javatypst;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class JavaTypst {

    private static volatile Instance instance;
    private static ExportFunction allocFn;
    private static ExportFunction deallocFn;
    private static ExportFunction renderFn;
    private static ExportFunction lastErrPtrFn;
    private static ExportFunction lastErrLenFn;

    private static final Object LOCK = new Object();

    private static void ensureInitialized() {
        if (instance != null) return;
        try (InputStream stream = JavaTypst.class.getResourceAsStream(
                "/io/github/fatihcatalkaya/javatypst/java_typst.wasm")) {
            if (stream == null) {
                throw new RuntimeException("java_typst.wasm not found on classpath");
            }
            var wasi = WasiPreview1.builder()
                    .withOptions(WasiOptions.builder().build())
                    .build();
            var store = new Store().addFunction(wasi.toHostFunctions());
            Instance newInstance = store.instantiate("java-typst", Parser.parse(stream));
            allocFn      = newInstance.export("alloc");
            deallocFn    = newInstance.export("dealloc");
            renderFn     = newInstance.export("render");
            lastErrPtrFn = newInstance.export("last_error_ptr");
            lastErrLenFn = newInstance.export("last_error_len");
            instance = newInstance;  // only set after all exports resolved
        } catch (Exception e) {
            throw new RuntimeException("Failed to load java_typst.wasm", e);
        }
    }

    /**
     * Renders Typst markup to a PDF document.
     *
     * @param content Typst markup source
     * @return PDF document as a byte array
     * @throws TypstRenderException if the Typst source fails to compile
     */
    public static byte[] render(String content) {
        synchronized (LOCK) {
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
            }
        }
    }

    private JavaTypst() {}
}
