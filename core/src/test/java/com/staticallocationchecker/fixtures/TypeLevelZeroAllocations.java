package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: type-level annotation should apply to every method. */
@ZeroAllocations
public class TypeLevelZeroAllocations {

    public Object first() {
        return new Object();
    }

    public Object second() {
        return new Object();
    }
}
