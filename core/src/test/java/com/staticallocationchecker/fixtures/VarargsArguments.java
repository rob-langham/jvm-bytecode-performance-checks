package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * Fixture: varargs call sites whose argument expressions allocate. Those allocations sit between
 * the synthesised array and the call that consumes it, so recognising the call site means looking
 * past them rather than stopping at the first one.
 */
public class VarargsArguments {

    static int count(Object... values) {
        return values.length;
    }

    static int countInts(int... values) {
        return values.length;
    }

    @ZeroAllocations
    public int boxedArguments(int a, long b) {
        return count(a, b);
    }

    @ZeroAllocations
    public int constructedArgument() {
        return count(new Object());
    }

    @ZeroAllocations
    public String formattedArguments(int n) {
        return String.format("%d", n);
    }

    @ZeroAllocations
    public int varargsWithinVarargs(int a) {
        return count(countInts(a, 2), "tail");
    }
}
