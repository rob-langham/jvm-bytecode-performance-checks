package com.staticallocationchecker.crossrelease;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The part of the matrix that cannot be written in Java 8 syntax: a record, whose generated
 * {@code toString()} builds a fresh String on every call.
 *
 * <p>Compiled only at 17, 21 and 25, and kept out of the shared fixture so that the shared
 * expectation stays identical at every level rather than growing a per-release exception.
 */
public class RecordHotPath {

    /** Its toString is a RECORD_TO_STRING; its equals and hashCode allocate nothing. */
    public record Level(long price, int quantity) {
    }

    private final Level level = new Level(1L, 2);

    /** Reaches the record's generated toString, which is where the allocation is reported. */
    @ZeroAllocations
    public String describe() {
        return level.toString();
    }

    /** Clean: equals and hashCode share the bootstrap but build nothing. */
    @ZeroAllocations
    public boolean sameAs(Level other) {
        return level.equals(other) && level.hashCode() == other.hashCode();
    }
}
