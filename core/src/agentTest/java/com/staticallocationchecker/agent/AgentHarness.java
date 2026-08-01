package com.staticallocationchecker.agent;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;

/**
 * Runs inside a JVM launched with {@code -javaagent}, exercises a warmup method, and prints what
 * the agent recorded. Everything it needs from the checker comes from the agent jar itself, which
 * the JVM appends to the system class path - so this also proves the jar is self-sufficient.
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

    public static void main(String[] args) throws Exception {
        Warmup warmup = new Warmup();
        for (int i = 0; i < 3; i++) {
            warmup.warm();
        }

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.staticallocationchecker:type=AllocationFlightRecorder");

        System.out.println("REGISTERED=" + server.isRegistered(name));
        System.out.println("TOTAL=" + server.getAttribute(name, "TotalAllocations"));
        TabularData sites = (TabularData) server.getAttribute(name, "Sites");
        System.out.println("SITES=" + sites.size());
        sites.values().forEach(row -> System.out.println("SITE=" + ((javax.management.openmbean.CompositeData) row).get("key")));
    }
}
