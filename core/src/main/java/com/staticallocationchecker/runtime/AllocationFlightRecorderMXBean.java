package com.staticallocationchecker.runtime;

import java.util.Map;

/** JMX management interface for the {@link AllocationFlightRecorder}. */
public interface AllocationFlightRecorderMXBean {

    /** Total allocations recorded across all warmup sites. */
    long getTotalAllocations();

    /** Per-site records, keyed by site key, exposed as JMX tabular data. */
    Map<String, SiteRecord> getSites();

    /** Clears all recorded sites. */
    void reset();
}
