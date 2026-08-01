package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture: a type-level warmup annotation, including a constructor that allocates. */
@AllocationsForWarmup
public class TypeLevelWarmupClass {

    static final Object STATIC = new Object();

    private final Object created;

    public TypeLevelWarmupClass() {
        this.created = new Object();
    }

    public Object first() {
        return new Object();
    }

    public Object second() {
        return new int[1];
    }

    public Object created() {
        return created;
    }
}
