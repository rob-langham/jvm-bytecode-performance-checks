package demo;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * A hot path with a lazily-initialised buffer: the shape the two annotations exist to describe.
 *
 * <p>{@link #price(int, long)} is on the latency-sensitive path and allocates nothing itself. It
 * reaches an allocation only through {@link #levels()}, which is a warmup boundary: the allocation
 * there is guarded by a null check and cached into a field, so it can fire at most once per
 * instance no matter how many times the hot path runs.
 *
 * <p>The static checker proves that shape. Only the runtime recorder can show that in a real
 * process the site fires once and then stops - which is what the demo driver measures.
 */
public final class PricingEngine {

    private long[] levels;
    private long checksum;

    /** Lazily allocates the level buffer. Guarded and cached: compliant warmup. */
    @AllocationsForWarmup
    long[] levels() {
        if (levels == null) {
            levels = new long[64];
        }
        return levels;
    }

    /** The hot path. Allocates nothing; reaches the buffer through the warmup boundary. */
    @ZeroAllocations
    public long price(int slot, long tick) {
        long[] buffer = levels();
        int index = slot & (buffer.length - 1);
        buffer[index] += tick;
        checksum += buffer[index];
        return checksum;
    }

    public long checksum() {
        return checksum;
    }
}
