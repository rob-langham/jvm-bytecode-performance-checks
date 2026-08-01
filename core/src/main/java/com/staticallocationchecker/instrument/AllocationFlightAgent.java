package com.staticallocationchecker.instrument;

import com.staticallocationchecker.runtime.AllocationFlightRecorder;
import java.lang.instrument.Instrumentation;

/**
 * Java agent that instruments {@code @AllocationsForWarmup} methods at class-load time so their
 * allocations are recorded in the {@link AllocationFlightRecorder}, and exposes the recorder over JMX.
 *
 * <p>Attach with {@code -javaagent:static-allocation-checker-core.jar}.
 */
public final class AllocationFlightAgent {

    private AllocationFlightAgent() {
    }

    /** Invoked by the JVM at startup when attached via {@code -javaagent}. */
    public static void premain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    /** Invoked by the JVM when the agent is attached to a running VM. */
    public static void agentmain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        AllocationFlightRecorder.register(AllocationFlightRecorder.instance());
        instrumentation.addTransformer(new WarmupClassFileTransformer());
    }
}
