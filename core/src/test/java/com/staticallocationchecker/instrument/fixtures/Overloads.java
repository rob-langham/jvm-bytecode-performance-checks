package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: two overloads of one name under different contracts, told apart only by line. */
public class Overloads {

    @ZeroAllocations
    public Object make(int size) {
        return new int[size];
    }

    public Object make(String text) {
        return new StringBuilder(text);
    }
}
