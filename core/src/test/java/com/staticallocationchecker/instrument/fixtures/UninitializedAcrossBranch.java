package com.staticallocationchecker.instrument.fixtures;

/**
 * Fixture: a branch between {@code NEW} and its constructor call. The frame at the branch target holds
 * the object as {@code Uninitialized(offset of the NEW)}, so a probe must not take that offset.
 */
public class UninitializedAcrossBranch {

    /** Public so a child loader that defines only the fixture can still reach it. */
    public static final class Holder {
        public final String value;

        public Holder(String value) {
            this.value = value;
        }
    }

    public Object make(boolean flag) {
        return new Holder(flag ? "a" : "b");
    }

    /** The same shape inside a loop, so the {@code NEW} is also reached by a backward jump. */
    public int loop(int n) {
        int made = 0;
        for (int i = 0; i < n; i++) {
            Holder holder = new Holder(i % 2 == 0 ? "even" : "odd");
            made += holder.value.isEmpty() ? 0 : 1;
        }
        return made;
    }
}
