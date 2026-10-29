package com.staticallocationchecker.instrument;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
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
 * Java agent that instruments {@code @AllocationsForWarmup} methods at class-load time so every
 * allocation they make is written to an {@link AllocationTrace}.
 *
 * <p>Attach at startup with {@code -javaagent:core-<version>-agent.jar=dir=<trace-directory>}, or to
 * a running VM with the attach API. The trace directory defaults to {@value #DEFAULT_DIRECTORY} in
 * the working directory. It is closed, and its buffers flushed, when the JVM shuts down normally.
 *
 * <p>On attach, classes already loaded are retransformed, since in a warmup-monitoring tool those
 * are precisely the classes worth observing.
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
        AllocationTrace trace = openTrace(directoryFrom(args));
        AllocationTrace.install(trace);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(trace), "allocation-trace-close"));

        boolean retransform = retransformExisting && instrumentation.isRetransformClassesSupported();
        instrumentation.addTransformer(new WarmupClassFileTransformer(trace), retransform);
        if (retransform) {
            retransformLoadedWarmupClasses(instrumentation);
        }
    }

    /** The {@code dir=} option of the agent arguments, or the default directory. */
    static Path directoryFrom(String args) {
        if (args != null) {
            for (String option : args.split(",")) {
                if (option.startsWith("dir=")) {
                    return Paths.get(option.substring("dir=".length()));
                }
            }
        }
        return Paths.get(DEFAULT_DIRECTORY);
    }

    private static AllocationTrace openTrace(Path directory) {
        try {
            return AllocationTrace.open(directory);
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
     * Retransforms already-loaded warmup classes so an attach sees them.
     *
     * <p>Retransformation always re-runs the transformer against the class's <em>original</em>
     * bytes, so this cannot stack a second set of probes on an already-instrumented class.
     */
    private static void retransformLoadedWarmupClasses(Instrumentation instrumentation) {
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (isRetransformableWarmupClass(instrumentation, loaded)) {
                targets.add(loaded);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        try {
            instrumentation.retransformClasses(targets.toArray(new Class<?>[0]));
        } catch (Throwable t) {
            report("failed to retransform " + targets.size()
                    + " already-loaded warmup class(es); they are NOT being monitored (" + t + ")");
        }
    }

    private static boolean isRetransformableWarmupClass(Instrumentation instrumentation, Class<?> type) {
        try {
            // Bootstrap classes cannot see the recorder the injected probe calls, so instrumenting
            // them would only produce NoClassDefFoundError at runtime.
            if (type.getClassLoader() == null || !instrumentation.isModifiableClass(type)) {
                return false;
            }
            return declaresWarmup(type);
        } catch (Throwable t) {
            // Reflecting over an arbitrary loaded class can fail on its own missing dependencies.
            // A class we cannot inspect is simply not a candidate.
            return false;
        }
    }

    private static boolean declaresWarmup(Class<?> type) {
        if (isWarmupAnnotated(type)) {
            return true;
        }
        for (AnnotatedElement member : type.getDeclaredMethods()) {
            if (isWarmupAnnotated(member)) {
                return true;
            }
        }
        for (AnnotatedElement member : type.getDeclaredConstructors()) {
            if (isWarmupAnnotated(member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWarmupAnnotated(AnnotatedElement element) {
        return element.isAnnotationPresent(AllocationsForWarmup.class);
    }

    private static void report(String message) {
        System.err.println("static-allocation-checker: " + message);
    }
}
