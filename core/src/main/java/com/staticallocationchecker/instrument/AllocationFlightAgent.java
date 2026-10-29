package com.staticallocationchecker.instrument;

import com.staticallocationchecker.runtime.AllocationTrace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.AnnotatedElement;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Java agent that records every allocation site in the application at class-load time, writing each
 * allocation to an {@link AllocationTrace}.
 *
 * <p>Attach at startup with {@code -javaagent:core-<version>-agent.jar=dir=<trace-directory>,warmup=<seconds>},
 * or to a running VM with the attach API. The trace directory defaults to {@value #DEFAULT_DIRECTORY} in
 * the working directory. The warm-up period, from the moment the agent starts, is what the report calls
 * warm-up: allocations in it are expected, and only those after it count as steady state. It is closed,
 * and its buffers flushed, when the JVM shuts down normally.
 *
 * <p>On attach, classes already loaded are retransformed, since they are exactly the classes a
 * running application has been using.
 */
public final class AllocationFlightAgent {

    /** Trace directory used when the agent is given no {@code dir=} option. */
    public static final String DEFAULT_DIRECTORY = "allocation-trace";

    private AllocationFlightAgent() {
    }

    /** Invoked by the JVM at startup when attached via {@code -javaagent}. */
    public static void premain(String args, Instrumentation instrumentation) {
        // Nothing of the application is loaded yet, so class-load-time transformation covers it.
        install(args, instrumentation, false);
    }

    /** Invoked by the JVM when the agent is attached to a running VM. */
    public static void agentmain(String args, Instrumentation instrumentation) {
        install(args, instrumentation, true);
    }

    private static void install(String args, Instrumentation instrumentation, boolean retransformExisting) {
        AllocationTrace trace = openTrace(directoryFrom(args), warmupNanosFrom(args));
        AllocationTrace.install(trace);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(trace), "allocation-trace-close"));

        boolean retransform = retransformExisting && instrumentation.isRetransformClassesSupported();
        instrumentation.addTransformer(new AllocationClassFileTransformer(trace), retransform);
        if (retransform) {
            retransformLoadedClasses(instrumentation);
        }
    }

    /** The {@code dir=} option of the agent arguments, or the default directory. */
    static Path directoryFrom(String args) {
        String dir = option(args, "dir");
        return Paths.get(dir != null ? dir : DEFAULT_DIRECTORY);
    }

    /**
     * The {@code warmup=} option, in seconds, as nanoseconds. Zero when absent: then every event is
     * steady state, and the report says so.
     */
    static long warmupNanosFrom(String args) {
        String seconds = option(args, "warmup");
        if (seconds == null) {
            return 0L;
        }
        double value = Double.parseDouble(seconds);
        if (value < 0) {
            throw new IllegalArgumentException("warmup must not be negative: " + seconds);
        }
        return Math.round(value * 1e9);
    }

    /** The value of {@code name=value} among comma-separated agent options, or null. */
    private static String option(String args, String name) {
        if (args != null) {
            for (String option : args.split(",")) {
                if (option.startsWith(name + "=")) {
                    return option.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private static AllocationTrace openTrace(Path directory, long warmupNanos) {
        try {
            return AllocationTrace.open(directory, warmupNanos);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open allocation trace in " + directory, e);
        }
    }

    private static void closeQuietly(AllocationTrace trace) {
        try {
            trace.close();
        } catch (IOException e) {
            report("failed to close the allocation trace: " + e);
        }
    }

    /**
     * Retransforms already-loaded classes so an attach sees them.
     *
     * <p>Retransformation always re-runs the transformer against the class's <em>original</em>
     * bytes, so this cannot stack a second set of probes on an already-instrumented class.
     */
    private static void retransformLoadedClasses(Instrumentation instrumentation) {
        // One class at a time: a batch fails as a whole on the first class the JVM refuses, and a
        // running application has plenty of classes that cannot be retransformed.
        int refused = 0;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!isRetransformable(instrumentation, loaded)) {
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Throwable t) {
                refused++;
            }
        }
        if (refused > 0) {
            report(refused + " already-loaded class(es) could not be retransformed; they are NOT being recorded");
        }
    }

    private static boolean isRetransformable(Instrumentation instrumentation, Class<?> type) {
        try {
            // Bootstrap classes cannot see the trace the injected probe calls, so instrumenting
            // them would only produce NoClassDefFoundError at runtime. The agent's own classes are
            // left alone for the same reason the transformer leaves them alone.
            return type.getClassLoader() != null
                    && instrumentation.isModifiableClass(type)
                    && !AllocationClassFileTransformer.isAgentClass(type.getName().replace('.', '/'));
        } catch (Throwable t) {
            // Asking about an arbitrary loaded class can fail on its own missing dependencies.
            // A class we cannot inspect is simply not a candidate.
            return false;
        }
    }

    private static void report(String message) {
        System.err.println("static-allocation-checker: " + message);
    }
}
