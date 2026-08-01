package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture: the three outcomes of the warmup contract. */
public class WarmupContract {

    private Object cached;
    private Object other;

    @AllocationsForWarmup
    public Object compliant() {
        // Guarded by a branch and cached into a field: compliant.
        if (cached == null) {
            cached = new Object();
        }
        return cached;
    }

    @AllocationsForWarmup
    public Object unconditional() {
        // Cached, but allocated on every path (not guarded).
        other = new Object();
        return other;
    }

    @AllocationsForWarmup
    public Object discarded(boolean create) {
        // Guarded, but the allocation is not cached into a field.
        if (create) {
            return new Object();
        }
        return null;
    }
}
