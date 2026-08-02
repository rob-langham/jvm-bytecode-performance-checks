package com.staticallocationchecker.examples;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import java.util.ArrayList;
import java.util.List;

/**
 * Warmup allocation under the guarded-and-cached contract.
 *
 * <p>Unlike the other examples this one is annotated, because the contract being demonstrated
 * <em>is</em> the annotation: {@code @AllocationsForWarmup} is what makes these allocations legal
 * at all, so there is nothing to show without it.
 */
public class BufferPool {

    private long[] buffer;
    private final List<long[]> pool = new ArrayList<>();

    /** Compliant: guarded by the null check, cached into a field. */
    @AllocationsForWarmup
    public long[] buffer(int size) {
        if (buffer == null) {
            buffer = new long[size];
        }
        return buffer;
    }

    /** Compliant: retained by a collection held in a field, which is still caching. */
    @AllocationsForWarmup
    public void prefill(int count) {
        if (pool.isEmpty()) {
            for (int i = 0; i < count; i++) {
                pool.add(new long[64]);
            }
        }
    }

    /** WARMUP_NOT_GUARDED: allocates on every call, so it never stops warming up. */
    @AllocationsForWarmup
    public long[] unguarded(int size) {
        buffer = new long[size];
        return buffer;
    }

    /** WARMUP_NOT_CACHED: guarded, but the result is discarded and made again later. */
    @AllocationsForWarmup
    public long[] uncached(boolean create) {
        if (create) {
            return new long[64];
        }
        return null;
    }

    /**
     * A hot path calling a warmup method. The walk stops at the boundary and treats the sanctioned
     * allocations as allowed, so starting here reports nothing.
     */
    public long[] hotPath() {
        return buffer(64);
    }
}
