package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: string concatenation, compiled to invokedynamic via StringConcatFactory. */
public class StringConcatenation {

    @ZeroAllocations
    public String concat(String a, int b) {
        return a + b;
    }
}
