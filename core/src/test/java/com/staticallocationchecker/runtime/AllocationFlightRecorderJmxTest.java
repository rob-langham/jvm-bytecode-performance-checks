package com.staticallocationchecker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;
import org.junit.jupiter.api.Test;

class AllocationFlightRecorderJmxTest {

    @Test
    void exposesRecorderThroughPlatformMBeanServer() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(() -> 7L);
        recorder.record("A#m:1:NEW");
        recorder.record("A#m:1:NEW");

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = AllocationFlightRecorder.register(recorder);
        try {
            assertEquals(2L, server.getAttribute(name, "TotalAllocations"));

            TabularData sites = (TabularData) server.getAttribute(name, "Sites");
            assertEquals(1, sites.size());

            server.invoke(name, "reset", new Object[0], new String[0]);
            assertEquals(0L, server.getAttribute(name, "TotalAllocations"));
        } finally {
            AllocationFlightRecorder.unregister();
        }
    }
}
