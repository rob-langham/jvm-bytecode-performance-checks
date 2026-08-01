package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: the annotations applied directly to constructors rather than to the whole type. */
public class AnnotatedConstructors {

    /** A constructor on a hot path, annotated without dragging in every other method. */
    public static class ZeroAllocationConstructor {
        private final Object value;

        @ZeroAllocations
        public ZeroAllocationConstructor(Object supplied) {
            this.value = supplied;
        }

        @ZeroAllocations
        public ZeroAllocationConstructor(int count) {
            this.value = new int[count];
        }

        /** Unannotated, so its allocation is nobody's business. */
        public ZeroAllocationConstructor() {
            this.value = new Object();
        }

        public Object value() {
            return value;
        }
    }

    /** A constructor that performs warmup allocation under the guarded-and-cached contract. */
    public static class WarmupConstructor {
        private Object cache;

        @AllocationsForWarmup
        public WarmupConstructor(boolean eager) {
            if (eager) {
                cache = new Object();
            }
        }

        public Object cache() {
            return cache;
        }
    }

    /** A warmup-annotated constructor that allocates unconditionally, violating the contract. */
    public static class NonCompliantWarmupConstructor {
        private Object cache;

        @AllocationsForWarmup
        public NonCompliantWarmupConstructor() {
            cache = new Object();
        }

        public Object cache() {
            return cache;
        }
    }
}
