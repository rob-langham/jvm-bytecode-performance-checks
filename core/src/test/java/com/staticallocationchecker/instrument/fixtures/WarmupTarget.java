package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture whose warmup method performs a single lazy allocation. */
public class WarmupTarget {

    private Object cache;

    @AllocationsForWarmup
    public Object warm() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }
}
