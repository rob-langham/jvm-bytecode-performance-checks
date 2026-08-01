package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture whose warmup method allocates unconditionally on every call. */
public class EagerWarmup {

    @AllocationsForWarmup
    public Object make() {
        return new Object();
    }
}
