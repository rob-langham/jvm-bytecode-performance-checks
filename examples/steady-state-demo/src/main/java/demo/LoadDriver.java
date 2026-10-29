package demo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives both engines under sustained load, so the agent has a realistic workload to record.
 *
 * <p>Run with the agent attached and give it a directory for the trace. Without the agent nothing is
 * instrumented and no trace is written:
 *
 * <pre>{@code
 * java -javaagent:core-0.1.0-SNAPSHOT-agent.jar=dir=build/trace \
 *      -cp build/classes:core-0.1.0-SNAPSHOT-agent.jar demo.LoadDriver
 * }</pre>
 *
 * <p>Then read the trace with {@code com.staticallocationchecker.trace.TraceReport build/trace build/classes}.
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

        for (int round = 1; round <= ROUNDS; round++) {
            runRound(pricing, resizing, round);
            System.out.printf("round %d done: %,d operations%n", round, OPERATIONS.get());
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

    private LoadDriver() {
    }
}
