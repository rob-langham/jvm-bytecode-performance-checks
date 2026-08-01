package demo.runtime;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/**
 * Statically identical to {@link PricingEngine}: guarded by a null-or-too-small check, cached into
 * a field. It satisfies the warmup contract exactly.
 *
 * <p>It also never stops allocating, because the buffer keeps being outgrown. No static analysis
 * can see that - only the runtime recorder can.
 */
public class ResizingCache {

    private double[] scratch;

    @AllocationsForWarmup
    public double[] scratch(int required) {
        if (scratch == null || scratch.length < required) {
            scratch = new double[required];
        }
        return scratch;
    }

    public double sum(long tick) {
        // The workload's high-water mark creeps up over time - a slowly growing order book, a
        // batch size that drifts with volume. The guard keeps letting an allocation through, so
        // this never converges the way the buffer-that-warms-up does.
        double[] buffer = scratch((int) (tick / 10_000) + 1);
        buffer[0] = tick;
        return buffer[0];
    }
}
