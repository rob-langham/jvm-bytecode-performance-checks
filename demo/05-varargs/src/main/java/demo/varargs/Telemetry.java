package demo.varargs;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * A varargs call site allocates an array you never wrote, on every call.
 *
 * <p>{@code emit(a, b)} and {@code emit(new long[] {a, b})} compile to <em>identical</em> bytecode,
 * so the two are told apart by the callee's {@code ACC_VARARGS} flag - which is why the categories
 * differ below even though the source looks equally innocent.
 */
public class Telemetry {

    static void emit(long... values) {
        // pretend this publishes a metric
    }

    static void emitArray(long[] values) {
        // takes a real array parameter, not varargs
    }

    /** VARARGS_ARRAY - the array is synthesised by the compiler at this call site. */
    @ZeroAllocations
    public void publish(long a, long b) {
        emit(a, b);
    }

    /** NEW_ARRAY - written by hand, for an ordinary array parameter. Same bytecode shape. */
    @ZeroAllocations
    public void publishExplicitArray(long a, long b) {
        emitArray(new long[] {a, b});
    }

    /** CLEAN - the array already exists, so passing it allocates nothing. */
    @ZeroAllocations
    public void publishExisting(long[] existing) {
        emit(existing);
    }

    /**
     * CLEAN - the usual fix: an arity-specific overload, so there is no array to synthesise.
     * Ugly, and the reason you want the checker to tell you it is necessary.
     */
    static void emit2(long a, long b) {
        // pretend this publishes a metric
    }

    @ZeroAllocations
    public void publishViaOverload(long a, long b) {
        emit2(a, b);
    }
}
