package com.staticallocationchecker;

/**
 * The contract a method is held to, resolved from its annotations exactly as the checker resolves it:
 * its own annotation, then its type's, then one inherited from a supertype it overrides.
 *
 * <p>Public so that the runtime report can ask the same question the checker answers, rather than
 * re-deriving the rules and disagreeing with them.
 */
public enum Contract {

    /** {@code @AllocationsForWarmup}: allocation is allowed, under the warmup contract. */
    WARMUP,

    /** {@code @ZeroAllocations}: no allocation is allowed. */
    ZERO_ALLOCATIONS,

    /** Both annotations on one declaration. The checker reports it rather than picking a winner. */
    CONFLICTING,

    /** No contract applies. */
    NONE
}
