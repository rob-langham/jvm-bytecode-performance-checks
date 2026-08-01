package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: one allocating helper reachable from two annotated entry points. */
public class SharedHelper {

    private Object helper() {
        return new Object();
    }

    @ZeroAllocations
    public Object entryA() {
        return helper();
    }

    @ZeroAllocations
    public Object entryB() {
        return helper();
    }
}
