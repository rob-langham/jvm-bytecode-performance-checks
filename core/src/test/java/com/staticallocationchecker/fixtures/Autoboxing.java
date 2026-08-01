package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: autoboxing a primitive into a wrapper. */
public class Autoboxing {

    @ZeroAllocations
    public Object box(int n) {
        Integer boxed = n;
        return boxed;
    }
}
