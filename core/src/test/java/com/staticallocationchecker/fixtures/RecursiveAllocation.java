package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: a self-recursive helper, to prove the walk terminates and reports once. */
public class RecursiveAllocation {

    @ZeroAllocations
    public Object entry() {
        return recurse(3);
    }

    private Object recurse(int n) {
        if (n <= 0) {
            return new Object();
        }
        return recurse(n - 1);
    }
}
