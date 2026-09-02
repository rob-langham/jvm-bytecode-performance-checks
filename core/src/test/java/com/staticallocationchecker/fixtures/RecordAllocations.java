package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * Fixture: a record's generated members, which compile to invokedynamic against
 * {@code java.lang.runtime.ObjectMethods}. Only toString allocates.
 */
public class RecordAllocations {

    public record Point(int x, int y) {
    }

    @ZeroAllocations
    public String describe(Point point) {
        return point.toString();
    }

    @ZeroAllocations
    public boolean compare(Point a, Point b) {
        return a.equals(b) && a.hashCode() == b.hashCode();
    }
}
