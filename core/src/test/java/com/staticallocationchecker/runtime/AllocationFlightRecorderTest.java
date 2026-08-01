package com.staticallocationchecker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AllocationFlightRecorderTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private AllocationFlightRecorder newRecorder() {
        return new AllocationFlightRecorder(clock::get);
    }

    @Test
    void recordsPerSiteCountAndTotal() {
        AllocationFlightRecorder recorder = newRecorder();

        recorder.record("A#m:1:NEW");
        recorder.record("B#n:2:NEW");
        recorder.record("A#m:1:NEW");

        assertEquals(3L, recorder.total());
        Map<String, SiteRecord> sites = recorder.snapshot();
        assertEquals(2L, sites.get("A#m:1:NEW").count());
        assertEquals(1L, sites.get("B#n:2:NEW").count());
    }

    @Test
    void tracksFirstAndLastSeenTimestamps() {
        AllocationFlightRecorder recorder = newRecorder();

        clock.set(1_000L);
        recorder.record("A#m:1:NEW");
        clock.set(5_000L);
        recorder.record("A#m:1:NEW");

        SiteRecord site = recorder.snapshot().get("A#m:1:NEW");
        assertEquals(1_000L, site.firstSeenMillis());
        assertEquals(5_000L, site.lastSeenMillis());
    }

    @Test
    void resetClearsAllSites() {
        AllocationFlightRecorder recorder = newRecorder();
        recorder.record("A#m:1:NEW");

        recorder.reset();

        assertEquals(0L, recorder.total());
        assertEquals(Map.of(), recorder.snapshot());
    }
}
