package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: calls a JDK method whose bytecode is not among the analysis roots. */
public class UnresolvableCall {

    @ZeroAllocations
    public int entry(String s) {
        return s.length();
    }
}
