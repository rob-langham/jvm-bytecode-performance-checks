package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: the three array-allocation opcodes. */
public class ArrayAllocations {

    @ZeroAllocations
    public Object primitiveArray() {
        return new int[10];
    }

    @ZeroAllocations
    public Object referenceArray() {
        return new String[10];
    }

    @ZeroAllocations
    public Object multiArray() {
        return new int[2][3];
    }
}
