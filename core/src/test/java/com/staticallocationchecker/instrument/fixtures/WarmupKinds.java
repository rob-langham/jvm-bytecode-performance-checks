package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/** Fixture exercising each allocation category, plus an exempt exception allocation. */
public class WarmupKinds {

    @AllocationsForWarmup
    public String warm(int n, StringBuilder sink) {
        Object[] array = new Object[1];               // NEW_ARRAY
        Integer boxed = n;                            // BOXING
        String concat = "x" + n;                      // STRING_CONCAT
        Runnable lambda = () -> sink.append('y');     // LAMBDA (captures sink)
        RuntimeException exempt = new IllegalStateException("unused"); // exempt: Throwable
        return concat + array.length + boxed + lambda + exempt.getMessage();
    }
}
