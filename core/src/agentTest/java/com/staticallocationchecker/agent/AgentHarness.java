package com.staticallocationchecker.agent;

import com.staticallocationchecker.annotations.AllocationsForWarmup;

/**
 * Runs inside a JVM launched with {@code -javaagent}, and exercises a warmup method. What the agent
 * recorded is read back from the trace directory by the test. Everything it needs from the checker
 * comes from the agent jar itself, which the JVM appends to the system class path - so this also
 * proves the jar is self-sufficient.
 *
 * <p>Deliberately a {@code main} rather than a unit test: the premain path, the manifest attributes
 * and class-load-time transformation only exist in a real JVM launch.
 */
public final class AgentHarness {

    private AgentHarness() {
    }

    /** Loaded after premain has installed the transformer, so it is rewritten on the way in. */
    public static class Warmup {
        private Object cache;

        @AllocationsForWarmup
        public Object warm() {
            if (cache == null) {
                cache = new Object();
            }
            return cache;
        }
    }

    public static void main(String[] args) {
        Warmup warmup = new Warmup();
        for (int i = 0; i < 3; i++) {
            warmup.warm();
        }
        System.out.println("DONE");
    }
}
