package demo;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * A warmup boundary that satisfies the static contract but never actually settles.
 *
 * <p>{@link #scratch(int)} is guarded (a path skips the allocation) and cached (the reference is
 * stored in a field), so the static checker passes it - correctly, because statically that is all
 * there is to see. Whether the guard stops being taken depends on the values the method is called
 * with, and a workload whose request sizes keep growing past the buffer reallocates forever.
 *
 * <p>This is the class of bug the runtime recorder exists to find: a site whose count keeps
 * climbing after warmup should have finished.
 */
public final class ResizingCache {

    private byte[] scratch;
    private long checksum;

    /** Guarded and cached, and still able to fire on every call if sizes keep changing. */
    @AllocationsForWarmup
    byte[] scratch(int size) {
        if (scratch == null || scratch.length < size) {
            scratch = new byte[size];
        }
        return scratch;
    }

    /** The hot path, reaching the allocation only through the warmup boundary. */
    @ZeroAllocations
    public long handle(int size) {
        byte[] buffer = scratch(size);
        buffer[0]++;
        checksum += buffer.length;
        return checksum;
    }

    public long checksum() {
        return checksum;
    }
}
