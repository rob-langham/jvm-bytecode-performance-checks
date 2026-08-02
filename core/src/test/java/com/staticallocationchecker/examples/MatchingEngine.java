package com.staticallocationchecker.examples;

/**
 * The same job as {@link OrderBook}, written to allocate nothing.
 *
 * <p>Each technique here answers a specific finding from that class: primitive tables instead of
 * boxed keys, a reused buffer instead of a per-call one, no string building on the hot path.
 */
public class MatchingEngine {

    private final long[] scratch = new long[64];
    private final long[] keys = new long[1024];
    private final long[] values = new long[1024];

    public long lookup(long orderId) {
        int slot = (int) (orderId & 1023);
        while (keys[slot] != 0) {
            if (keys[slot] == orderId) {
                return values[slot];
            }
            slot = (slot + 1) & 1023;
        }
        return -1;
    }

    /** Calls a helper, which is walked transitively and is clean all the way down. */
    public long process(long orderId) {
        return normalise(lookup(orderId));
    }

    private long normalise(long value) {
        return value < 0 ? 0 : value;
    }

    public void record(int index, long value) {
        if (index >= 0 && index < scratch.length) {
            scratch[index] = value;
        }
    }
}
