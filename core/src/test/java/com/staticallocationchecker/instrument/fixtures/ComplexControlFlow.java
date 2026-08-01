package com.staticallocationchecker.instrument.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixture whose warmup methods contain the control-flow shapes that produce stack-map frames:
 * branches, loops, exception handlers, switches and long expression stacks.
 *
 * <p>The instrumenter inserts a stack-neutral probe before each allocation and recomputes only
 * {@code maxStack}; if that assumption were wrong, verifying these methods would fail at load time.
 */
public class ComplexControlFlow {

    private Object cache;
    private final List<Object> retained = new ArrayList<>();

    @AllocationsForWarmup
    public Object branches(int n) {
        if (n > 10) {
            cache = new Object();
        } else if (n > 5) {
            cache = new int[n];
        } else {
            cache = "x" + n;
        }
        return cache;
    }

    @AllocationsForWarmup
    public int loop(int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            retained.add(new Object());
            total += i;
        }
        while (total > 100) {
            retained.add(new int[1]);
            total -= 50;
        }
        return total;
    }

    @AllocationsForWarmup
    public Object exceptionHandlers(boolean fail) {
        try {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            cache = new Object();
        } catch (IllegalStateException e) {
            cache = new Object();
        } finally {
            retained.add(new Object());
        }
        return cache;
    }

    @AllocationsForWarmup
    public Object switchStatement(int n) {
        switch (n) {
            case 0:
                cache = new Object();
                break;
            case 1:
                cache = new int[2];
                break;
            default:
                cache = new String[1];
                break;
        }
        return cache;
    }

    /** A deep expression stack, so an inserted probe cannot rely on an empty stack. */
    @AllocationsForWarmup
    public String deepStack(int a, int b) {
        return String.join("-", "p" + a, "q" + b, String.valueOf(new int[a].length), new Object().toString());
    }

    /** Allocation inside a nested lambda body, which compiles to a separate synthetic method. */
    @AllocationsForWarmup
    public Runnable nestedLambda(StringBuilder sink) {
        return () -> sink.append(new Object());
    }

    /** An ordinary method that happens to be usable as a method reference. */
    public Object ordinaryFactory() {
        return new Object();
    }

    /**
     * Creates a lambda by method reference. The referenced method is ordinary code with its own
     * contract, and must not become warmup code merely by being referenced from here.
     */
    @AllocationsForWarmup
    public java.util.function.Supplier<Object> viaMethodReference() {
        return this::ordinaryFactory;
    }

    /** A lambda whose body creates a further lambda, so selection has to be transitive. */
    @AllocationsForWarmup
    public java.util.function.Supplier<Runnable> nestedLambdaInLambda(StringBuilder sink) {
        return () -> () -> sink.append(new Object());
    }

    /** Two allocations of the same category on a single source line. */
    @AllocationsForWarmup
    public Object[] sameLine(boolean a, boolean b) {
        Object x = null; Object y = null;
        if (a) { x = new Object(); } if (b) { y = new Object(); }
        return new Object[] {x, y};
    }
}
