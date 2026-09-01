package com.staticallocationchecker;

/** The kind of allocation an instruction performs. */
public enum AllocationCategory {
    NEW,
    NEW_ARRAY,
    BOXING,
    STRING_CONCAT,
    VARARGS_ARRAY,
    /**
     * A record's generated {@code toString()}, which builds a fresh String on every call. Its
     * generated {@code equals} and {@code hashCode} share the same bootstrap but allocate nothing.
     */
    RECORD_TO_STRING,
    /** A capturing lambda / method reference, which allocates an instance per evaluation. */
    LAMBDA,
}
