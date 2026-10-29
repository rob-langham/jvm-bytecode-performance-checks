package demo.runtime;

/**
 * Drives both engines under load, so the agent has a workload to record.
 *
 * <p>Both classes pass the static checker with zero findings. Only the trace tells them apart: one
 * settles, the other never does. Run it through {@code ./gradlew -p demo :07-runtime-flight-recorder:verifyRun},
 * which reads the trace the agent writes.
 */
public final class LoadDriver {

    private static final int ROUNDS = 6;
    private static final int OPERATIONS_PER_ROUND = 5_000_000;

    private LoadDriver() {
    }

    public static void main(String[] args) {
        PricingEngine pricing = new PricingEngine();
        ResizingCache cache = new ResizingCache();

        long operations = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            for (int i = 0; i < OPERATIONS_PER_ROUND; i++) {
                pricing.price(operations);
                cache.sum(operations);
                operations++;
            }
            System.out.printf("round %d done: %,d operations%n", round, operations);
        }
    }
}
