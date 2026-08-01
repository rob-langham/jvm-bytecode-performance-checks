package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: a zero-allocation method that directly allocates via {@code new}. */
public class DirectNew {

    @ZeroAllocations
    public Object make() {
        return new Object();
    }
}
