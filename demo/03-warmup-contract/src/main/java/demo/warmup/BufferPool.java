package demo.warmup;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;
import java.util.ArrayList;
import java.util.List;

/**
 * Real zero-allocation code still has to allocate its buffers once. {@code @AllocationsForWarmup}
 * permits that, but only under a contract: each allocation must be <em>guarded</em> (some path
 * skips it) and <em>cached</em> (the reference is retained).
 *
 * <p>That is the lazy-init shape, and nothing else.
 */
public class BufferPool {

    private long[] buffer;
    private final List<long[]> pool = new ArrayList<>();
    private Object leaked;

    /** COMPLIANT - guarded by the null check, cached into a field. */
    @AllocationsForWarmup
    public long[] buffer(int size) {
        if (buffer == null) {
            buffer = new long[size];
        }
        return buffer;
    }

    /** COMPLIANT - retained by a collection held in a field, which is still caching. */
    @AllocationsForWarmup
    public void prefill(int count) {
        if (pool.isEmpty()) {
            for (int i = 0; i < count; i++) {
                pool.add(new long[64]);
            }
        }
    }

    /** VIOLATION: WARMUP_NOT_GUARDED - allocates on every call, so it never stops warming up. */
    @AllocationsForWarmup
    public long[] unguarded(int size) {
        buffer = new long[size];
        return buffer;
    }

    /** VIOLATION: WARMUP_NOT_CACHED - guarded, but the result is thrown away and re-made later. */
    @AllocationsForWarmup
    public long[] uncached(boolean create) {
        if (create) {
            return new long[64];
        }
        return null;
    }

    /**
     * A zero-allocation method may call a warmup method. The walk stops at the boundary and treats
     * the sanctioned allocations as allowed, so this reports nothing.
     */
    @ZeroAllocations
    public long[] hotPath() {
        return buffer(64);
    }

    /** Keeps {@code leaked} used, so the field is not merely dead weight. */
    public Object leaked() {
        return leaked;
    }
}
