package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture with one warmup method and one ordinary allocating method. */
public class MixedWarmup {

    private Object cache;

    @AllocationsForWarmup
    public Object warm() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }

    public Object hot() {
        return new Object();
    }
}
