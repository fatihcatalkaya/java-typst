package io.github.fatihcatalkaya.javatypst;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AotBenchmarkTest {

    private static final String CONTENT = "= Hello, World!\n_Lorem_ *ipsum* sit dolor amet";
    private static final int RUNS = 40;

    @Test
    public void benchmarkInterpreterVsAot() {
        long[] interpMs = measure(false);
        long[] aotMs = measure(true);

        System.out.println("=== AOT Benchmark Results ===");
        System.out.printf("%-12s  %8s  %8s  %8s%n", "Mode", "Init(ms)", "Avg(ms)", "Total(ms)");
        printRow("Interpreter", interpMs);
        printRow("AOT", aotMs);
    }

    private long[] measure(boolean aot) {
        JavaTypst.reset();
        if (aot) JavaTypst.enableAot();

        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            byte[] pdf = JavaTypst.render(CONTENT);
            times[i] = (System.nanoTime() - start) / 1_000_000;
            assertNotNull(pdf);
            assertTrue(pdf.length > 0);
        }
        return times;
    }

    private void printRow(String label, long[] times) {
        long total = 0;
        for (long t : times) total += t;
        long avg = total / times.length;
        System.out.printf("%-12s  %8d  %8d  %8d%n", label, times[0], avg, total);
    }
}
