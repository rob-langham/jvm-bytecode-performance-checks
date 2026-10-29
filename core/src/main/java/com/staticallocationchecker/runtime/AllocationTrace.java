package com.staticallocationchecker.runtime;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Writes every allocation that an instrumented warmup site makes to a binary trace file.
 *
 * <p>Two files are produced in the trace directory:
 *
 * <ul>
 *   <li>{@value #EVENTS_FILE} - one fixed-size 12-byte record per allocation: a big-endian {@code int}
 *       site code, then a big-endian {@code long} timestamp in nanoseconds ({@link System#nanoTime()}).
 *       Records are never split across writes, so the file is always a whole number of records.</li>
 *   <li>{@value #KEYS_FILE} - a text file with one {@code code<TAB>siteKey} line per site, written
 *       when the site is first instrumented. The key says where the allocation is in the bytecode:
 *       {@code class#method:line@offset:CATEGORY}.</li>
 * </ul>
 *
 * <p>The recorder does no analysis. Turning the trace into numbers, and tying each code back to the
 * annotations that govern it, is {@link com.staticallocationchecker.trace.TraceReport}'s job.
 *
 * <p>Events are buffered per thread and written in whole-record chunks, so the hot path is a
 * thread-local lookup and a few stores. Buffers from threads that end early are flushed on
 * {@link #close()}. A JVM that is killed rather than shut down loses whatever is still buffered.
 */
public final class AllocationTrace implements Closeable {

    /** Bytes per event: an int site code and a long timestamp. */
    public static final int RECORD_BYTES = 12;

    /** The binary event log. */
    public static final String EVENTS_FILE = "allocations.bin";

    /** The text key file mapping site codes to site keys. */
    public static final String KEYS_FILE = "allocations.keys";

    private static final int BUFFER_RECORDS = 4096;

    /** The process-wide trace that instrumented code writes to, or null when none is installed. */
    private static volatile AllocationTrace instance;

    private final FileChannel events;
    private final BufferedWriter keys;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Map<String, Integer> codes = new HashMap<>();
    private final List<Buffer> buffers = new ArrayList<>();
    private final ThreadLocal<Buffer> local = ThreadLocal.withInitial(this::newBuffer);
    private volatile boolean closed;

    private AllocationTrace(Path directory, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.events = FileChannel.open(directory.resolve(EVENTS_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.keys = Files.newBufferedWriter(directory.resolve(KEYS_FILE), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** Opens a trace in {@code directory}, creating it if needed, timed by {@link System#nanoTime()}. */
    public static AllocationTrace open(Path directory) throws IOException {
        return open(directory, System::nanoTime);
    }

    /** Opens a trace whose timestamps come from {@code clock}. Intended for tests. */
    public static AllocationTrace open(Path directory, LongSupplier clock) throws IOException {
        Files.createDirectories(directory);
        return new AllocationTrace(directory, clock);
    }

    /** Installs {@code trace} as the destination for instrumented sites (or null to stop recording). */
    public static void install(AllocationTrace trace) {
        instance = trace;
    }

    /** The installed trace, or null. */
    public static AllocationTrace instance() {
        return instance;
    }

    /**
     * Static entry point the instrumented bytecode calls at each allocation site. Kept separate from
     * the instance method so the probe is a plain {@code INVOKESTATIC}.
     *
     * <p>Never throws: a failure to record must not change what the application does.
     */
    public static void recordSite(int code) {
        AllocationTrace trace = instance;
        if (trace != null) {
            trace.record(code);
        }
    }

    /**
     * The code for {@code siteKey}, assigning and persisting a new one on first sight. The same key
     * always gets the same code, so re-instrumenting a class (for example on retransformation) does
     * not create a second code for it.
     *
     * @throws IllegalStateException if the trace has been closed
     * @throws UncheckedIOException if the key file cannot be written
     */
    public int siteCode(String siteKey) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("allocation trace is closed");
            }
            Integer existing = codes.get(siteKey);
            if (existing != null) {
                return existing;
            }
            int code = codes.size();
            codes.put(siteKey, code);
            try {
                keys.write(code + "\t" + siteKey + "\n");
                keys.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + KEYS_FILE, e);
            }
            return code;
        }
    }

    /** Records one allocation at the site with {@code code}, stamped with the current clock. */
    public void record(int code) {
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        Buffer buffer = local.get();
        synchronized (buffer) {
            if (buffer.bytes.remaining() < RECORD_BYTES) {
                flush(buffer);
            }
            buffer.bytes.putInt(code).putLong(now);
        }
    }

    /** Writes out every buffered event now, leaving the trace open. */
    public void flush() {
        List<Buffer> pending;
        synchronized (lock) {
            pending = new ArrayList<>(buffers);
        }
        for (Buffer buffer : pending) {
            synchronized (buffer) {
                flush(buffer);
            }
        }
    }

    /** Writes out every buffered event and closes both files. Safe to call more than once. */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        flush();
        synchronized (lock) {
            try {
                events.close();
            } finally {
                keys.close();
            }
        }
    }

    private Buffer newBuffer() {
        Buffer buffer = new Buffer();
        synchronized (lock) {
            buffers.add(buffer);
        }
        return buffer;
    }

    /**
     * Writes a buffer's complete records. The caller holds the buffer's monitor. A buffer belongs to
     * one thread, so this runs under that thread's monitor (or under {@link #close()} once the trace
     * is closed); the file write itself is serialised on {@link #lock}.
     */
    private void flush(Buffer buffer) {
        buffer.bytes.flip();
        try {
            synchronized (lock) {
                while (buffer.bytes.hasRemaining()) {
                    events.write(buffer.bytes);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A closed or failed trace drops events; it must not throw into the application.
            // Failing silently would be indistinguishable from a quiet program, so say so once.
            if (!buffer.reported) {
                buffer.reported = true;
                System.err.println("static-allocation-checker: dropping allocation events (" + e + ")");
            }
        } finally {
            buffer.bytes.clear();
        }
    }

    /** One thread's pending records. Guarded by its own monitor. */
    private static final class Buffer {
        final ByteBuffer bytes = ByteBuffer.allocate(BUFFER_RECORDS * RECORD_BYTES);
        boolean reported;
    }
}
