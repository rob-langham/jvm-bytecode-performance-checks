package com.staticallocationchecker.examples;

/**
 * A varargs call site allocates an array nobody wrote, on every call.
 *
 * <p>{@code emit(a, b)} and {@code emitArray(new long[] {a, b})} compile to identical bytecode, so
 * only the callee's {@code ACC_VARARGS} flag separates them.
 */
public class Telemetry {

    static void emit(long... values) {
    }

    static void emitArray(long[] values) {
    }

    static void emit2(long a, long b) {
    }

    /** VARARGS_ARRAY: the array is synthesised here by the compiler. */
    public void publish(long a, long b) {
        emit(a, b);
    }

    /** NEW_ARRAY: written by hand, for an ordinary array parameter. */
    public void publishExplicitArray(long a, long b) {
        emitArray(new long[] {a, b});
    }

    /** Clean: the array already exists. */
    public void publishExisting(long[] existing) {
        emit(existing);
    }

    /** Clean: the usual fix, an arity-specific overload with no array to synthesise. */
    public void publishViaOverload(long a, long b) {
        emit2(a, b);
    }
}
