package demo.clean;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The same job as the failing scenario, written to allocate nothing. This one really is clean, and
 * the build proves it on every compile.
 *
 * <p>Every technique here is the ordinary answer to the corresponding finding in
 * {@code 01-zero-allocation-basics}: primitive keys instead of boxed ones, reused buffers instead
 * of per-call ones, and no string building on the hot path at all.
 */
public class MatchingEngine {

    /** Reused rather than allocated per call. */
    private final long[] scratch = new long[64];

    /** A primitive open-addressing table: no boxing, because there are no wrapper keys. */
    private final long[] keys = new long[1024];
    private final long[] values = new long[1024];

    @ZeroAllocations
    public long lookup(long orderId) {
        int slot = (int) (orderId & 1023);
        while (keys[slot] != 0) {
            if (keys[slot] == orderId) {
                return values[slot];
            }
            slot = (slot + 1) & 1023;
        }
        return -1;
    }

    @ZeroAllocations
    public long best(int count) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < count && i < scratch.length; i++) {
            if (scratch[i] > best) {
                best = scratch[i];
            }
        }
        return best;
    }

    /** Calls a helper, which is walked transitively - clean all the way down. */
    @ZeroAllocations
    public long process(long orderId) {
        long value = lookup(orderId);
        return normalise(value);
    }

    private long normalise(long value) {
        return value < 0 ? 0 : value;
    }

    @ZeroAllocations
    public void record(int index, long value) {
        if (index >= 0 && index < scratch.length) {
            scratch[index] = value;
        }
    }
}
