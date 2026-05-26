package io.github.fatihcatalkaya.javatypst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Concurrency behaviour of {@link JavaTypst}.
 *
 * <p><b>Investigation summary.</b> Every public render entry point and every configuration
 * mutator in {@link JavaTypst} funnels through one private static {@code LOCK}. The state it
 * guards is all of the engine's mutable bookkeeping: the WASM {@code Instance} and its export
 * functions, {@code aotEnabled}, {@code packageResolver}, {@code packageCacheDir},
 * {@code diskCache}, the per-call {@code currentPackageUrlMap}, and the host-side
 * {@code pendingFetches} map used by the two-call fetch protocol. The auxiliary classes are
 * thread-safe in isolation too: {@link PackageDiskCache} uses an intrinsic lock and
 * {@link HttpPackageResolver} reuses a static {@link java.net.http.HttpClient} (documented
 * thread-safe).
 *
 * <p>Consequences for callers:
 * <ul>
 *   <li>Calls from many threads are <i>safe</i> — results are correct and independent — but
 *       they are <i>not</i> parallel. The single WASM instance executes them one at a time.</li>
 *   <li>Per-call inputs/fonts/packages do not leak across threads because the Rust side rebuilds
 *       the {@code TypstEngine} on every compile, and Java clears {@code currentPackageUrlMap}
 *       and {@code pendingFetches} in {@code finally} blocks inside the lock.</li>
 *   <li>A {@link TypstRenderException} on one thread cannot poison another thread's render: the
 *       same {@code finally} blocks free all WASM allocations on the failure path.</li>
 *   <li>Configuration calls that need a fresh engine ({@link JavaTypst#enableAot()},
 *       {@link JavaTypst#setPackageCacheDirectory}) reject themselves with
 *       {@link IllegalStateException} once any render has initialized the instance, regardless
 *       of which thread initialized it.</li>
 * </ul>
 *
 * <p>The tests below exercise each of these properties under contention.
 */
public class ConcurrencyTest {

    private static final int THREADS = 4;
    private static final int ITERATIONS = 3;

    private static byte[] customFont;

    @BeforeAll
    static void enableAotForSpeed() throws IOException {
        // Use AOT mode so the per-render cost (~80 ms vs ~550 ms in the interpreter) keeps the
        // suite quick even with dozens of serialized renders per test.
        JavaTypst.reset();
        JavaTypst.enableAot();
        try (InputStream is = ConcurrencyTest.class.getResourceAsStream("texgyrecursor-regular.otf")) {
            assertNotNull(is, "test font missing from resources");
            customFont = is.readAllBytes();
        }
    }

    /** Returns the trimmed text content extracted from a single-page PDF. */
    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).strip();
        }
    }

    /** Returns every PostScript font name embedded across all pages of the PDF. */
    private static Set<String> embeddedFontNames(byte[] pdf) throws IOException {
        Set<String> names = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName key : res.getFontNames()) {
                    PDFont font = res.getFont(key);
                    if (font != null && font.getName() != null) {
                        names.add(font.getName());
                    }
                }
            }
        }
        return names;
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int value) throws Exception;
    }

    /**
     * Runs {@code task} on {@code threads} workers, all released by a single latch so they race
     * into the API, and re-throws the first failure with its original assertion intact.
     */
    private static void runConcurrently(int threads, ThrowingIntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            int tid = t;
            Callable<Void> work = () -> {
                start.await();
                task.accept(tid);
                return null;
            };
            futures.add(pool.submit(work));
        }
        start.countDown();
        try {
            for (Future<Void> f : futures) {
                try {
                    f.get(120, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    if (cause instanceof Exception e) throw e;
                    throw ex;
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "thread pool did not terminate");
        }
    }

    @Test
    public void plainRendersFromManyThreadsAllSucceed() throws Exception {
        runConcurrently(THREADS, tid -> {
            for (int i = 0; i < ITERATIONS; i++) {
                String expected = "thread" + tid + "-iter" + i;
                byte[] pdf = JavaTypst.render(expected);
                assertEquals(expected, pdfText(pdf));
            }
        });
    }

    @Test
    public void inputsAreIsolatedAcrossConcurrentRenders() throws Exception {
        // If `sys.inputs` leaked between concurrent calls (e.g. the typst-as-lib injected library
        // was somehow shared), a thread would observe another thread's value. Each thread asserts
        // it sees only the unique tag it submitted.
        runConcurrently(THREADS, tid -> {
            for (int i = 0; i < ITERATIONS; i++) {
                String expected = "tid=" + tid + "/iter=" + i;
                byte[] pdf = JavaTypst.renderWithInputs("#sys.inputs.at(\"k\")", Map.of("k", expected));
                assertEquals(expected, pdfText(pdf));
            }
        });
    }

    @Test
    public void fontsAreIsolatedAcrossConcurrentRenders() throws Exception {
        // Even-tid threads pass the custom font and require it to be embedded; odd-tid threads
        // pass an empty fonts list and require the custom font to be ABSENT. Cross-leakage would
        // flip one of the assertions.
        runConcurrently(THREADS, tid -> {
            boolean useCustom = tid % 2 == 0;
            List<byte[]> fonts = useCustom ? List.of(customFont) : List.of();
            for (int i = 0; i < ITERATIONS; i++) {
                byte[] pdf = JavaTypst.renderWithFonts("#set text(font: \"TeX Gyre Cursor\")\nrun-" + tid, fonts);
                boolean present = embeddedFontNames(pdf).stream().anyMatch(n -> n.contains("TeXGyreCursor"));
                assertEquals(useCustom, present, "tid=" + tid + " expected custom font present=" + useCustom);
            }
        });
    }

    @Test
    public void mixedApiMethodsAreThreadSafe() throws Exception {
        // Threads pick a different render entry point by tid % 3 so the same engine is hit
        // through all three code paths concurrently. Each thread submits a unique tag and
        // verifies the rendered text round-trips.
        runConcurrently(THREADS, tid -> {
            for (int i = 0; i < ITERATIONS; i++) {
                String tag = "t" + tid + "i" + i;
                byte[] pdf =
                        switch (tid % 3) {
                            case 0 -> JavaTypst.render(tag);
                            case 1 -> JavaTypst.renderWithInputs("#sys.inputs.at(\"v\")", Map.of("v", tag));
                            default -> JavaTypst.renderWithFonts(tag, List.of(customFont));
                        };
                assertEquals(tag, pdfText(pdf));
            }
        });
    }

    @Test
    public void failuresInOneThreadDoNotPoisonOthers() throws Exception {
        // Half the threads always render invalid markup; half always render valid markup. The
        // valid ones must all succeed, the invalid ones must all throw, and a final post-storm
        // render must still produce a correct PDF — proving the failure path's `finally` blocks
        // cleaned up after every failed render.
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        runConcurrently(THREADS * 2, tid -> {
            for (int i = 0; i < ITERATIONS; i++) {
                if (tid % 2 == 0) {
                    String tag = "ok-" + tid + "-" + i;
                    assertEquals(tag, pdfText(JavaTypst.render(tag)));
                    okCount.incrementAndGet();
                } else {
                    assertThrows(TypstRenderException.class, () -> JavaTypst.render("#let x ="));
                    failCount.incrementAndGet();
                }
            }
        });
        assertEquals(THREADS * ITERATIONS, okCount.get());
        assertEquals(THREADS * ITERATIONS, failCount.get());
        assertEquals("after-storm", pdfText(JavaTypst.render("after-storm")));
    }

    @Test
    public void concurrentInitializationDoesNotDoubleInitialize() throws Exception {
        // Force a fresh instance, then race many threads into the very first render. If the LOCK
        // in `ensureInitialized` did not single-flight the init, the second initializer would
        // overwrite freshly-bound exports and an in-flight render would NPE on a half-built
        // instance. All threads must instead see a consistent, fully-built engine.
        JavaTypst.reset();
        JavaTypst.enableAot();
        runConcurrently(THREADS * 2, tid -> {
            byte[] pdf = JavaTypst.render("init-" + tid);
            assertEquals("init-" + tid, pdfText(pdf));
        });
    }

    @Test
    public void enableAotIsRejectedOnceInstanceIsLiveEvenFromAnotherThread() throws Exception {
        // After any thread has caused initialization, calling `enableAot()` from any other
        // thread must throw IllegalStateException — the guard is global to the engine, not
        // scoped to a single thread.
        JavaTypst.render("warmup");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> f = pool.submit((Runnable) JavaTypst::enableAot);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
            assertInstanceOf(
                    IllegalStateException.class,
                    ex.getCause(),
                    "enableAot called from another thread after init must throw IllegalStateException");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void rendersBlockWhileTheLockIsHeldElsewhere() throws Exception {
        // Reach in via reflection to acquire the private LOCK, then verify that another thread's
        // call to render() really cannot make progress until the lock is released — concrete
        // evidence that the public API is serialized through this single monitor, not merely
        // protected by per-method synchronization that could be bypassed.
        Field lockField = JavaTypst.class.getDeclaredField("LOCK");
        lockField.setAccessible(true);
        Object lock = lockField.get(null);

        // Make sure init has already happened so the contested code path is just the render,
        // not the one-shot initialization that also runs under LOCK.
        JavaTypst.render("warmup");

        CountDownLatch otherStarted = new CountDownLatch(1);
        AtomicBoolean otherCompleted = new AtomicBoolean();
        Thread other;
        synchronized (lock) {
            other = new Thread(
                    () -> {
                        otherStarted.countDown();
                        JavaTypst.render("blocked");
                        otherCompleted.set(true);
                    },
                    "concurrency-test-blocked-renderer");
            other.start();
            // The blocked thread reaches the latch before it calls render(); wait until we are
            // sure it has at least started running, then give it more than a render's worth of
            // time to (incorrectly) sneak past the lock.
            assertTrue(otherStarted.await(2, TimeUnit.SECONDS), "blocked thread never started");
            Thread.sleep(300);
            assertFalse(
                    otherCompleted.get(),
                    "render() returned while another thread still held LOCK — serialization is broken");
        }
        // Lock released here. The blocked render should now proceed.
        other.join(10_000);
        assertTrue(otherCompleted.get(), "blocked render did not complete after LOCK was released");
    }
}
