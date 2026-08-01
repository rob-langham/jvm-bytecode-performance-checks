package com.staticallocationchecker.instrument.fixtures;

/** Fixture with an allocating method but no warmup annotation. */
public class NotWarmup {

    public Object make() {
        return new Object();
    }
}
