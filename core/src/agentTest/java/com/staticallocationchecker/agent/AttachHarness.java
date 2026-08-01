package com.staticallocationchecker.agent;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Runs in a JVM started <em>without</em> any agent. It loads and exercises a warmup class first,
 * then waits for a marker file, by which time the test will have attached the agent to this
 * process. Only retransformation of an already-loaded class can make the second round visible.
 */
public final class AttachHarness {

    private AttachHarness() {
    }

    /** Loaded and initialised long before the agent arrives. */
    public static class Warmup {
        private Object cache;

        @AllocationsForWarmup
        public Object warm() {
            if (cache == null) {
                cache = new Object();
            }
            return cache;
        }

        /** Clears the cache so the guarded allocation can fire again after attach. */
        public void forget() {
            cache = null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);

        Warmup warmup = new Warmup();
        warmup.warm();

        System.out.println("LOADED");
        System.out.flush();

        long deadlineMillis = System.currentTimeMillis() + 60_000;
        while (!Files.exists(marker) && System.currentTimeMillis() < deadlineMillis) {
            Thread.sleep(50);
        }
        if (!Files.exists(marker)) {
            System.out.println("TIMEOUT");
            return;
        }

        // The class was loaded before the agent existed; only a retransform can have probed it.
        warmup.forget();
        warmup.warm();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.staticallocationchecker:type=AllocationFlightRecorder");
        System.out.println("REGISTERED=" + server.isRegistered(name));
        System.out.println("TOTAL=" + (server.isRegistered(name)
                ? server.getAttribute(name, "TotalAllocations") : "n/a"));
    }
}
