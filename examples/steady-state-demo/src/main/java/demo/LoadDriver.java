package demo;

import com.staticallocationchecker.runtime.AllocationFlightRecorder;
import com.staticallocationchecker.runtime.SiteRecord;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives both engines under sustained load and samples the flight recorder as it goes, so the
 * difference between "warms up and stops" and "never stops" is visible in the counts.
 *
 * <p>Run with the agent attached; without it nothing is instrumented and every count is zero:
 *
 * <pre>{@code
 * java -javaagent:core-0.1.0-SNAPSHOT-agent.jar \
 *      -cp build/classes:core-0.1.0-SNAPSHOT-agent.jar demo.LoadDriver
 * }</pre>
 */
public final class LoadDriver {

    private static final int THREADS = 4;
    private static final int ROUNDS = 8;
    private static final long OPS_PER_ROUND = 2_000_000L;

    private static final AtomicLong OPERATIONS = new AtomicLong();
    private static final AtomicLong SINK = new AtomicLong();

    public static void main(String[] args) throws Exception {
        PricingEngine pricing = new PricingEngine();
        ResizingCache resizing = new ResizingCache();

        System.out.printf("%-6s %12s %10s %8s   %s%n", "round", "operations", "recorded", "new", "sites");
        long previousTotal = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            runRound(pricing, resizing, round);
            previousTotal = report(round, previousTotal);
        }
        // Keeps the JIT from proving the whole workload dead.
        System.out.println("checksums: " + pricing.checksum() + " " + resizing.checksum() + " " + SINK.get());
    }

    /** One round of load across several threads, on both engines. */
    private static void runRound(PricingEngine pricing, ResizingCache resizing, int round) throws Exception {
        Thread[] workers = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            workers[t] = new Thread(() -> {
                long local = 0;
                for (long i = 0; i < OPS_PER_ROUND / THREADS; i++) {
                    local += pricing.price((int) i, i);
                    // Request sizes grow with the round, so the buffer is never big enough for long.
                    local += resizing.handle(64 + (int) (i % 32) * round);
                    OPERATIONS.incrementAndGet();
                }
                SINK.addAndGet(local);
            }, "load-" + t);
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    /**
     * Prints the recorder's view after a round and returns the running total.
     *
     * <p>The "new" column is the point of the whole exercise: a site that has warmed up contributes
     * nothing to it, however much load goes through the method.
     */
    private static long report(int round, long previousTotal) {
        AllocationFlightRecorder recorder = AllocationFlightRecorder.instance();
        Map<String, SiteRecord> sites = recorder.snapshot();
        StringBuilder rendered = new StringBuilder();
        sites.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> rendered.append(shortKey(e.getKey()))
                        .append("=").append(e.getValue().count()).append("  "));
        long total = recorder.total();
        System.out.printf(
                "%-6d %12d %10d %8d   %s%n",
                round, OPERATIONS.get(), total, total - previousTotal, rendered.toString().trim());
        return total;
    }

    /** Trims a site key down to Class#method for display. */
    private static String shortKey(String siteKey) {
        int colon = siteKey.indexOf(':');
        String head = colon < 0 ? siteKey : siteKey.substring(0, colon);
        int dot = head.lastIndexOf('.');
        return dot < 0 ? head : head.substring(dot + 1);
    }

    private LoadDriver() {
    }
}
