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

    /** Takes a real array parameter, not varargs. */
    static int total(int[] values) {
        return values.length;
    }

    /**
     * An array built for an ordinary array parameter. This compiles to the same bytecode shape as
     * a varargs call site, so only the callee's ACC_VARARGS flag tells the two apart.
     */
    @ZeroAllocations
    public int passesExplicitArrayToAnOrdinaryParameter() {
        return total(new int[] {1, 2, 3});
    }
}
