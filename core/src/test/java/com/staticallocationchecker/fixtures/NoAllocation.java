package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: a zero-allocation method that allocates nothing. */
public class NoAllocation {

    @ZeroAllocations
    public int add(int a, int b) {
        return a + b;
    }
}
