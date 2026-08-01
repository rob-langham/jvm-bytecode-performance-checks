package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: a zero-allocation method reaching allocations only through a warmup boundary. */
public class WarmupBoundary {

    private Object cache;

    @ZeroAllocations
    public Object hot() {
        return warmup();
    }

    @AllocationsForWarmup
    private Object warmup() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }
}
