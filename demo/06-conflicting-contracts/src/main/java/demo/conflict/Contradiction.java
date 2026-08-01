package demo.conflict;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The two contracts contradict each other: one forbids allocation, the other permits it under
 * conditions. Claiming both on one declaration is a mistake, and it is reported as
 * {@code CONFLICTING_CONTRACTS} rather than resolved by picking a winner - silently applying the
 * looser one would hide the error.
 */
public class Contradiction {

    /** VIOLATION: CONFLICTING_CONTRACTS - both annotations on a single declaration. */
    @ZeroAllocations
    @AllocationsForWarmup
    public Object confused() {
        return new Object();
    }

    /**
     * NOT a conflict. A type-level contract sets the default and a method-level one is a
     * deliberate, more specific exception to it. The nearer declaration wins.
     */
    @ZeroAllocations
    public static class PriceLevels {
        private long[] levels;

        /** The exception: this method is allowed to warm up. */
        @AllocationsForWarmup
        public long[] warm(int size) {
            if (levels == null) {
                levels = new long[size];
            }
            return levels;
        }

        /** Covered by the type-level contract, and clean. */
        public long at(int index) {
            return levels[index];
        }
    }
}
