package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: allocating exceptions is exempt; other allocations are not. */
public class ExceptionAllocation {

    /** A user-defined exception, to exercise the index-then-reflection hierarchy climb. */
    public static class CustomException extends RuntimeException {
    }

    @ZeroAllocations
    public void throwsJdkException(boolean fail) {
        if (fail) {
            throw new IllegalStateException("bad state");
        }
    }

    @ZeroAllocations
    public void throwsCustomException(boolean fail) {
        if (fail) {
            throw new CustomException();
        }
    }

    @ZeroAllocations
    public Object allocatesObject() {
        return new Object();
    }
}
