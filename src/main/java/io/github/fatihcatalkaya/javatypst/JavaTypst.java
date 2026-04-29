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

    private static Instance instance;
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
            instance = store.instantiate("java-typst", Parser.parse(stream));
            allocFn      = instance.export("alloc");
            deallocFn    = instance.export("dealloc");
            renderFn     = instance.export("render");
            lastErrPtrFn = instance.export("last_error_ptr");
            lastErrLenFn = instance.export("last_error_len");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load java_typst.wasm", e);
        }
    }

    public static byte[] render(String content) {
        synchronized (LOCK) {
            ensureInitialized();
            Memory memory = instance.memory();

            byte[] inputBytes = content.getBytes(StandardCharsets.UTF_8);
            int inLen = inputBytes.length;
            int inPtr = (int) allocFn.apply(inLen)[0];
            memory.write(inPtr, inputBytes);

            int outLenPtr = (int) allocFn.apply(4)[0];

            int outPtr = (int) renderFn.apply(inPtr, inLen, outLenPtr)[0];

            if (outPtr == 0) {
                int errPtr = (int) lastErrPtrFn.apply()[0];
                int errLen = (int) lastErrLenFn.apply()[0];
                String errorMsg = memory.readString(errPtr, errLen);
                deallocFn.apply(outLenPtr, 4);
                deallocFn.apply(inPtr, inLen);
                throw new TypstRenderException(errorMsg);
            }

            int outLen = memory.readInt(outLenPtr);
            byte[] pdfBytes = memory.readBytes(outPtr, outLen);

            deallocFn.apply(outPtr, outLen);
            deallocFn.apply(outLenPtr, 4);
            deallocFn.apply(inPtr, inLen);

            return pdfBytes;
        }
    }
}
