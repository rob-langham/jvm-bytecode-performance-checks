package demo.basics;

import com.staticallocationchecker.annotations.ZeroAllocations;
import java.util.HashMap;
import java.util.Map;

/**
 * Every allocation category the checker recognises, in the shape it actually turns up in.
 *
 * <p>None of these look like allocations in review. That is the point: the only one with a visible
 * {@code new} is the first.
 */
public class OrderBook {

    private final Map<Long, String> levels = new HashMap<>();
    private final StringBuilder log = new StringBuilder();

    /** NEW - the obvious one, and the only one code review reliably catches. */
    @ZeroAllocations
    public Object directNew() {
        return new Object();
    }

    /** NEW_ARRAY - a scratch buffer allocated per call instead of being reused. */
    @ZeroAllocations
    public long[] scratchBuffer(int size) {
        return new long[size];
    }

    /** BOXING - the map key is a Long, so every lookup boxes the primitive. */
    @ZeroAllocations
    public String lookup(long orderId) {
        return levels.get(orderId);
    }

    /** STRING_CONCAT - invisible in the source, an invokedynamic in the bytecode. */
    @ZeroAllocations
    public String describe(long orderId, double price) {
        return "order " + orderId + " @ " + price;
    }

    /** LAMBDA - captures `price`, so a new instance is allocated on every evaluation. */
    @ZeroAllocations
    public Runnable onFill(double price) {
        return () -> log.append(price);
    }

    /** A non-capturing lambda links to a cached singleton and allocates nothing. */
    @ZeroAllocations
    public Runnable noOp() {
        return () -> {
        };
    }

    /** Throwable allocation is exempt: the exceptional path is not the hot path. */
    @ZeroAllocations
    public void reject(boolean invalid) {
        if (invalid) {
            throw new IllegalStateException("rejected");
        }
    }
}
