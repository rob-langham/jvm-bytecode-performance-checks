package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: how the two annotations interact, and which member kinds they reach. */
public class AnnotationSemantics {

    /** Both annotations on one method: the precedence between them is what this pins down. */
    public static class BothOnOneMethod {
        @ZeroAllocations
        @AllocationsForWarmup
        public Object entry() {
            return new Object();
        }
    }

    /**
     * A warmup method inside a zero-allocation type. Not a conflict: the type-level contract is
     * the default and the method-level one is a deliberate, more specific exception to it.
     */
    @ZeroAllocations
    public static class WarmupMethodInZeroAllocationType {
        private Object cache;

        @AllocationsForWarmup
        public Object warm() {
            if (cache == null) {
                cache = new Object();
            }
            return cache;
        }

        public Object hot() {
            return cache;
        }
    }

    /** A type-level {@code @ZeroAllocations} reaches the constructor and the static initialiser. */
    @ZeroAllocations
    public static class TypeLevelReachesInitialisers {
        static final Object STATIC_FIELD = new Object();

        private final Object field;

        public TypeLevelReachesInitialisers() {
            this.field = new Object();
        }

        public Object field() {
            return field;
        }
    }

    /** A type-level {@code @AllocationsForWarmup} applies the warmup contract to every method. */
    @AllocationsForWarmup
    public static class TypeLevelWarmup {
        private Object cache;

        public Object compliant() {
            if (cache == null) {
                cache = new Object();
            }
            return cache;
        }

        public Object unconditional() {
            cache = new Object();
            return cache;
        }
    }

    /** Static and private methods are annotated the same way instance methods are. */
    public static class MemberKinds {
        @ZeroAllocations
        public static Object staticMethod() {
            return new Object();
        }

        @ZeroAllocations
        private Object privateMethod() {
            return new Object();
        }

        /** Keeps {@link #privateMethod()} reachable so javac does not warn. */
        public Object callsPrivate() {
            return privateMethod();
        }
    }

    /** An annotation on a superclass method is not inherited by an override. */
    public static class AnnotatedParent {
        @ZeroAllocations
        public Object make() {
            return null;
        }
    }

    /** Overrides an annotated method without repeating the annotation. */
    public static class UnannotatedOverride extends AnnotatedParent {
        @Override
        public Object make() {
            return new Object();
        }
    }
}
