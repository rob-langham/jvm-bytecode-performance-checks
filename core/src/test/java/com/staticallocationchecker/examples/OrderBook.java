package com.staticallocationchecker.examples;

import java.util.HashMap;
import java.util.Map;

/**
 * A hot path with every allocation category on it, written the way each actually turns up.
 *
 * <p>Deliberately carries no annotations. The integration tests name their starting point instead,
 * which keeps these examples out of every other test's results and demonstrates the targeting the
 * annotations exist to avoid needing.
 */
public class OrderBook {

    private final Map<Long, String> levels = new HashMap<>();
    private final StringBuilder log = new StringBuilder();

    /** NEW - the only one code review reliably catches. */
    public Object directNew() {
        return new Object();
    }

    /** NEW_ARRAY - a scratch buffer allocated per call instead of reused. */
    public long[] scratchBuffer(int size) {
        return new long[size];
    }

    /** BOXING - the key is a Long, so every lookup boxes the primitive. */
    public String lookup(long orderId) {
        return levels.get(orderId);
    }

    /** STRING_CONCAT - invisible in the source, an invokedynamic in the bytecode. */
    public String describe(long orderId, double price) {
        return "order " + orderId + " @ " + price;
    }

    /** LAMBDA - captures price, so a new instance on every evaluation. */
    public Runnable onFill(double price) {
        return () -> log.append(price);
    }

    /** Captures nothing, so it links to a cached singleton and allocates nothing. */
    public Runnable noOp() {
        return () -> {
        };
    }

    /** Throwable allocation is exempt: the exceptional path is not the hot path. */
    public void reject(boolean invalid) {
        if (invalid) {
            throw new IllegalStateException("rejected");
        }
    }
}
