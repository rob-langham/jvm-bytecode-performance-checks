package com.staticallocationchecker.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Reads a trace directory written by {@link AllocationTrace}.
 *
 * <p>Events are streamed, not loaded, because a long run produces far more of them than fit in
 * memory. A partial record at the end of the event file - left by a process that died mid-write - is
 * ignored; every complete record before it is still delivered.
 */
public final class AllocationTraceReader {

    /** Receives events in file order. */
    @FunctionalInterface
    public interface EventVisitor {
        void event(int code, long nanos);
    }

    private static final int BUFFER_BYTES = AllocationTrace.RECORD_BYTES * 8192;

    private AllocationTraceReader() {
    }

    /** Streams every complete event in {@code directory} to {@code visitor}, in the order written. */
    public static void forEachEvent(Path directory, EventVisitor visitor) throws IOException {
        Path file = directory.resolve(AllocationTrace.EVENTS_FILE);
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int carried = 0;
            int read;
            while ((read = in.read(buffer, carried, buffer.length - carried)) != -1) {
                int usable = carried + read - (carried + read) % AllocationTrace.RECORD_BYTES;
                for (int at = 0; at < usable; at += AllocationTrace.RECORD_BYTES) {
                    visitor.event(readInt(buffer, at), readLong(buffer, at + 4));
                }
                carried = carried + read - usable;
                System.arraycopy(buffer, usable, buffer, 0, carried);
            }
        }
    }

    /**
     * The nanoTime at which the warm-up period ended, or empty if the trace has no warm-up file (a trace
     * written by something other than {@link AllocationTrace}, or an older one).
     */
    public static OptionalLong warmupEnd(Path directory) throws IOException {
        Path file = directory.resolve(AllocationTrace.WARMUP_FILE);
        if (!Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("endNanos=")) {
                return OptionalLong.of(Long.parseLong(line.substring("endNanos=".length()).trim()));
            }
        }
        return OptionalLong.empty();
    }

    /** The site keys in {@code directory}, by code, in the order they were assigned. */
    public static Map<Integer, String> readKeys(Path directory) throws IOException {
        Map<Integer, String> keys = new LinkedHashMap<>();
        for (String line : Files.readAllLines(directory.resolve(AllocationTrace.KEYS_FILE),
                StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                keys.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
            }
        }
        return keys;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static long readLong(byte[] b, int at) {
        return ((long) readInt(b, at) << 32) | (readInt(b, at + 4) & 0xFFFFFFFFL);
    }
}
