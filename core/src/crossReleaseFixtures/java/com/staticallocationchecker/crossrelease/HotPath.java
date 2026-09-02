package com.staticallocationchecker.crossrelease;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The cross-release fixture: one source file, compiled unchanged at every release level the checker
 * claims to support, and expected to produce exactly the same findings at all of them.
 *
 * <p>Deliberately plain Java 8 syntax, so that the file itself is not what varies between levels -
 * only what javac emits for it is. What varies is substantial: below release 9 a concatenation is a
 * {@link StringBuilder} chain rather than an invokedynamic, and the constant pool and stack maps
 * move around underneath everything else. A category-keyed expectation is supposed to survive all
 * of that, and this is where that claim is actually tested.
 *
 * <p>The annotations are applied directly rather than through a named entry point, which is only
 * possible because they are compiled at release 8 themselves, along with the rest of the library
 * (see core/build.gradle.kts). The release-8 row of this matrix is therefore also the end-to-end
 * proof
 * that a release-8 project can use this library at all.
 */
public class HotPath {

    private long sink;

    /** NEW. */
    @ZeroAllocations
    public Object directNew() {
        return new Object();
    }

    /** NEW_ARRAY, primitive. */
    @ZeroAllocations
    public long[] primitiveArray(int size) {
        return new long[size];
    }

    /** NEW_ARRAY, reference. */
    @ZeroAllocations
    public String[] referenceArray(int size) {
        return new String[size];
    }

    /** BOXING - implicit, which is the way it actually reaches a hot path. */
    @ZeroAllocations
    public Object box(long id) {
        return id;
    }

    /** STRING_CONCAT: an invokedynamic from release 9, a StringBuilder chain below it. */
    @ZeroAllocations
    public String concat(long id) {
        return "order " + id;
    }

    /** STRING_CONCAT, once per concatenation expression however many times the loop runs it. */
    @ZeroAllocations
    public String concatInLoop(int count) {
        String text = "";
        for (int i = 0; i < count; i++) {
            text = text + i;
        }
        return text;
    }

    /** LAMBDA: captures id and this, so a fresh instance per evaluation. */
    @ZeroAllocations
    public Runnable capturingLambda(final long id) {
        return () -> add(id);
    }

    /** Clean: captures nothing, so it links to a cached singleton. */
    @ZeroAllocations
    public Runnable nonCapturingLambda() {
        return () -> {
        };
    }

    /** LAMBDA: a bound method reference captures the receiver. */
    @ZeroAllocations
    public Runnable boundMethodReference() {
        return this::tick;
    }

    /** VARARGS_ARRAY: an array nobody wrote, synthesised at the call site. */
    @ZeroAllocations
    public void varargsConstantArguments() {
        emit(1L, 2L);
    }

    /** VARARGS_ARRAY, plus the BOXING of the argument that goes into it. */
    @ZeroAllocations
    public void varargsBoxedArguments(int value) {
        collect("count", value);
    }

    /** Clean: the array already exists, so there is nothing to synthesise. */
    @ZeroAllocations
    public void varargsExistingArray(long[] existing) {
        emit(existing);
    }

    /** Clean: Throwable allocation is exempt - the exceptional path is not the hot path. */
    @ZeroAllocations
    public void reject(boolean invalid) {
        if (invalid) {
            throw new IllegalStateException("rejected");
        }
    }

    /** Clean: arithmetic on primitives, which is what the annotation is asking for. */
    @ZeroAllocations
    public long clean(long id) {
        return id * 31 + 7;
    }

    private void tick() {
        sink++;
    }

    private void add(long id) {
        sink += id;
    }

    static void emit(long... values) {
    }

    static void collect(Object... values) {
    }
}
