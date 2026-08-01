package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: the implicit array javac synthesises at a varargs call site. */
public class Varargs {

    static int count(int... values) {
        return values.length;
    }

    static int countObjects(Object... values) {
        return values.length;
    }

    @ZeroAllocations
    public int passesPrimitiveVarargs() {
        return count(1, 2, 3);
    }

    @ZeroAllocations
    public int passesObjectVarargs(String a, String b) {
        return countObjects(a, b);
    }

    /** Passing an existing array to a varargs parameter allocates nothing. */
    @ZeroAllocations
    public int passesExistingArray(int[] existing) {
        return count(existing);
    }
}
