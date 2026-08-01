package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: the annotated method allocates nothing itself but calls a helper that does. */
public class TransitiveCaller {

    @ZeroAllocations
    public Object entry() {
        return helper();
    }

    private Object helper() {
        return new Object();
    }
}
