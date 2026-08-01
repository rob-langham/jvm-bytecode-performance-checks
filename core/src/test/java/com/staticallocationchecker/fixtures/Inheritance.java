package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: methods inherited rather than declared on the call site's static type. */
public class Inheritance {

    /** Declares an allocating method that the subclass inherits unchanged. */
    public static class AllocatingParent {
        Object inherited() {
            return new Object();
        }
    }

    /** Inherits {@code inherited()} without redeclaring it. */
    public static class AllocatingChild extends AllocatingParent {
    }

    /** Declares a non-allocating method that the subclass inherits unchanged. */
    public static class CleanParent {
        int inheritedClean() {
            return 42;
        }
    }

    /** Inherits {@code inheritedClean()} without redeclaring it. */
    public static class CleanChild extends CleanParent {
    }

    private final AllocatingChild allocating = new AllocatingChild();
    private final CleanChild clean = new CleanChild();

    @ZeroAllocations
    public Object callsInheritedAllocatingMethod() {
        return allocating.inherited();
    }

    @ZeroAllocations
    public int callsInheritedCleanMethod() {
        return clean.inheritedClean();
    }
}
