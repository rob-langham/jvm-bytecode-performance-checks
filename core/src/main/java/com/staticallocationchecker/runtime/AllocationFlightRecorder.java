package com.staticallocationchecker.runtime;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Records allocations that fire inside {@code @AllocationsForWarmup} methods, aggregated per site.
 *
 * <p>Instrumented warmup methods call {@link #record(String)} with a constant site key. Counts are
 * kept per site alongside first- and last-seen timestamps, so a site that keeps firing (or fires
 * long after warmup) is visible to monitoring.
 */
public final class AllocationFlightRecorder implements AllocationFlightRecorderMXBean {

    /** The JMX name under which the recorder is registered. */
    public static final String OBJECT_NAME = "com.staticallocationchecker:type=AllocationFlightRecorder";

    private static volatile AllocationFlightRecorder instance =
            new AllocationFlightRecorder(System::currentTimeMillis);

    private final ConcurrentHashMap<String, Site> sites = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public AllocationFlightRecorder(LongSupplier clock) {
        this.clock = clock;
    }

    /** The process-wide recorder that instrumented code reports to. */
    public static AllocationFlightRecorder instance() {
        return instance;
    }

    /** Installs the process-wide recorder (used by the agent and by tests). */
    public static void install(AllocationFlightRecorder recorder) {
        instance = recorder;
    }

    /**
     * Static entry point invoked by instrumented warmup methods. Kept separate from the instance
     * {@link #record(String)} because a class cannot declare both a static and an instance method
     * with the same signature.
     */
    public static void recordSite(String siteKey) {
        instance.record(siteKey);
    }

    /** Registers {@code recorder} as an MXBean with the platform server, replacing any prior one. */
    public static ObjectName register(AllocationFlightRecorder recorder) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            server.registerMBean(recorder, name);
            return name;
        } catch (JMException e) {
            throw new IllegalStateException("Failed to register AllocationFlightRecorder MBean", e);
        }
    }

    /** Unregisters the recorder MXBean if present. */
    public static void unregister() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (JMException e) {
            throw new IllegalStateException("Failed to unregister AllocationFlightRecorder MBean", e);
        }
    }

    /** Records one allocation at the given site. */
    public void record(String siteKey) {
        long now = clock.getAsLong();
        sites.computeIfAbsent(siteKey, k -> new Site()).observe(now);
    }

    /** Clears all recorded sites. */
    public void reset() {
        sites.clear();
    }

    /** The total number of allocations recorded across all sites. */
    public long total() {
        return sites.values().stream().mapToLong(s -> s.count.sum()).sum();
    }

    /** An immutable snapshot of every recorded site. */
    public Map<String, SiteRecord> snapshot() {
        return Collections.unmodifiableMap(sites.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toRecord())));
    }

    @Override
    public long getTotalAllocations() {
        return total();
    }

    @Override
    public Map<String, SiteRecord> getSites() {
        return snapshot();
    }

    private static final class Site {
        private final LongAdder count = new LongAdder();
        private final AtomicLong firstSeen = new AtomicLong(Long.MIN_VALUE);
        private volatile long lastSeen;

        void observe(long now) {
            count.increment();
            firstSeen.compareAndSet(Long.MIN_VALUE, now);
            lastSeen = now;
        }

        SiteRecord toRecord() {
            long first = firstSeen.get();
            return new SiteRecord(count.sum(), first == Long.MIN_VALUE ? 0L : first, lastSeen);
        }
    }
}
