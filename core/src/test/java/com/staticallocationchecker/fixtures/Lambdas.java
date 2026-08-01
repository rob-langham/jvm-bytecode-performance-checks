package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: capturing vs non-capturing lambdas. */
public class Lambdas {

    private static final Runnable NOOP = () -> {
    };

    @ZeroAllocations
    public Runnable stateless() {
        // Captures nothing: the JVM links this to a cached singleton, no per-call allocation.
        return () -> NOOP.run();
    }

    @ZeroAllocations
    public Runnable capturing(StringBuilder sink) {
        // Captures sink: a new instance is allocated on every evaluation.
        return () -> sink.append('x');
    }
}
