package com.staticallocationchecker.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Set;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The JMX surface, which is how the recorder is actually consumed in production. The
 * {@code SiteRecord} to {@code CompositeData} mapping is done reflectively by the platform, so it
 * breaks silently at registration time if the bean shape drifts.
 */
class AllocationFlightRecorderMBeanTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void unregister() {
        AllocationFlightRecorder.unregister();
    }

    @Test
    void exposesTotalAndSitesAsReadableAttributes() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(() -> 7L);
        ObjectName name = AllocationFlightRecorder.register(recorder);

        MBeanInfo info = server.getMBeanInfo(name);
        Set<String> attributes = Set.of(info.getAttributes()).stream()
                .map(MBeanAttributeInfo::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(attributes.contains("TotalAllocations"), () -> "attributes were " + attributes);
        assertTrue(attributes.contains("Sites"), () -> "attributes were " + attributes);
    }

    @Test
    void mapsEverySiteRecordFieldThroughCompositeData() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(() -> 4_242L);
        recorder.record("A#m:1:NEW");
        recorder.record("A#m:1:NEW");
        ObjectName name = AllocationFlightRecorder.register(recorder);

        TabularData sites = (TabularData) server.getAttribute(name, "Sites");
        CompositeData row = (CompositeData) sites.values().iterator().next();
        CompositeData value = (CompositeData) row.get("value");

        assertEquals("A#m:1:NEW", row.get("key"));
        assertEquals(2L, value.get("count"));
        assertEquals(4_242L, value.get("firstSeenMillis"));
        assertEquals(4_242L, value.get("lastSeenMillis"));
    }

    @Test
    void exposesAnEmptyTableBeforeAnythingIsRecorded() throws Exception {
        ObjectName name = AllocationFlightRecorder.register(new AllocationFlightRecorder(() -> 1L));

        TabularData sites = (TabularData) server.getAttribute(name, "Sites");

        assertNotNull(sites, "an empty recorder must still map to a table, not null");
        assertTrue(sites.isEmpty());
        assertEquals(0L, server.getAttribute(name, "TotalAllocations"));
    }

    @Test
    void resetIsInvokableThroughJmxAndClearsTheTable() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(() -> 1L);
        recorder.record("A#m:1:NEW");
        ObjectName name = AllocationFlightRecorder.register(recorder);

        server.invoke(name, "reset", new Object[0], new String[0]);

        assertEquals(0L, server.getAttribute(name, "TotalAllocations"));
        assertTrue(((TabularData) server.getAttribute(name, "Sites")).isEmpty());
    }

    @Test
    void registeringTwiceReplacesTheEarlierRegistration() throws Exception {
        AllocationFlightRecorder first = new AllocationFlightRecorder(() -> 1L);
        first.record("A#m:1:NEW");
        AllocationFlightRecorder second = new AllocationFlightRecorder(() -> 1L);

        AllocationFlightRecorder.register(first);
        ObjectName name = AllocationFlightRecorder.register(second);

        assertEquals(0L, server.getAttribute(name, "TotalAllocations"),
                "the second registration should be the one now serving reads");
    }

    @Test
    void unregisteringWhenNotRegisteredIsANoOp() {
        assertDoesNotThrow(AllocationFlightRecorder::unregister);
        assertDoesNotThrow(AllocationFlightRecorder::unregister);
    }

    @Test
    void unregisterRemovesTheBeanFromTheServer() throws Exception {
        ObjectName name = AllocationFlightRecorder.register(new AllocationFlightRecorder(() -> 1L));
        assertTrue(server.isRegistered(name));

        AllocationFlightRecorder.unregister();

        assertFalse(server.isRegistered(name));
    }

    @Test
    void objectNameConstantIsAValidObjectName() throws Exception {
        assertDoesNotThrow(() -> new ObjectName(AllocationFlightRecorder.OBJECT_NAME));
        assertEquals("com.staticallocationchecker",
                new ObjectName(AllocationFlightRecorder.OBJECT_NAME).getDomain());
    }
}
