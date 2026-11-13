package com.staticallocationchecker;

import com.staticallocationchecker.runtime.AllocationTrace;
import com.staticallocationchecker.runtime.AllocationTraceReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Installs a trace in a test directory for the duration of one test, and reads what it recorded
 * back. Restores whatever trace was installed before, since the trace is process-wide state.
 */
public final class TraceFixture implements AutoCloseable {

    private final AllocationTrace previous;
    private final AllocationTrace trace;
    private final Path directory;

    public TraceFixture(Path directory) throws IOException {
        this.directory = directory;
        this.previous = AllocationTrace.instance();
        this.trace = AllocationTrace.open(directory, () -> 1_000L);
        AllocationTrace.install(trace);
    }

    public AllocationTrace trace() {
        return trace;
    }

    /** The site-code assigner that an instrumenter should be given. */
    public ToIntFunction<String> siteCodes() {
        return trace::siteCode;
    }

    /** Total events written so far. Flushes first, so buffered events are counted. */
    public long total() {
        return counts().values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Events recorded in one method, by name. Every allocation in a class is now recorded, so a
     * whole-trace total also counts constructors and helpers the test did not mean to measure.
     */
    public long totalIn(String method) {
        return counts().entrySet().stream()
                .filter(e -> e.getKey().contains("#" + method + ":"))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /** The site keys that have fired at least once. */
    public Set<String> firedKeys() {
        return counts().keySet();
    }

    /** Events per site key, for sites that fired. */
    public Map<String, Long> counts() {
        trace.flush();
        try {
            Map<Integer, String> keys = AllocationTraceReader.readKeys(directory);
            Map<String, Long> counts = new HashMap<>();
            AllocationTraceReader.forEachEvent(directory,
                    (code, millis) -> counts.merge(keys.get(code), 1L, Long::sum));
            return counts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The distinct site keys assigned so far, whether or not they fired. */
    public Set<String> assignedKeys() {
        trace.flush();
        try {
            return new HashSet<>(AllocationTraceReader.readKeys(directory).values());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        AllocationTrace.install(previous);
        trace.close();
    }
}
