package com.staticallocationchecker;

/** The kind of allocation an instruction performs. */
public enum AllocationCategory {
    NEW,
    NEW_ARRAY,
    BOXING,
    STRING_CONCAT,
    VARARGS_ARRAY,
    /** A capturing lambda / method reference, which allocates an instance per evaluation. */
    LAMBDA,
}
