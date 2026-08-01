package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture: annotated for warmup but allocating nothing, so there is nothing to instrument. */
public class WarmupWithoutAllocation {

    @AllocationsForWarmup
    public int warm(int a, int b) {
        return a + b;
    }
}
