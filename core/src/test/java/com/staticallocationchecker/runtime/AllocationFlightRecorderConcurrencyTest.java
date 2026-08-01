package com.staticallocationchecker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The recorder is written for concurrent use - {@code ConcurrentHashMap}, {@code LongAdder}, a
 * compare-and-set on first-seen and a volatile last-seen - but every other test drives it from one
 * thread, so nothing would catch a regression to a plain {@code long} or a dropped CAS.
 */
class AllocationFlightRecorderConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 25_000;

    @Test
    @Timeout(60)
    void doesNotLoseUpdatesWhenManyThreadsRecordTheSameSite() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(System::currentTimeMillis);

        runConcurrently(recorder, i -> "shared#m:1:NEW");

        assertEquals((long) THREADS * PER_THREAD, recorder.total());
        assertEquals((long) THREADS * PER_THREAD, recorder.snapshot().get("shared#m:1:NEW").count());
    }

    @Test
    @Timeout(60)
    void doesNotLoseSitesWhenManyThreadsRecordDistinctSites() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(System::currentTimeMillis);

        runConcurrently(recorder, i -> "site" + i + "#m:1:NEW");

        assertEquals(THREADS, recorder.snapshot().size(), "one site per thread");
        assertEquals((long) THREADS * PER_THREAD, recorder.total());
        assertTrue(recorder.snapshot().values().stream().allMatch(s -> s.count() == PER_THREAD),
                () -> "every site should have its own full count: " + recorder.snapshot());
    }

    @Test
    @Timeout(60)
    void keepsFirstSeenNoLaterThanLastSeenUnderContention() throws Exception {
        AtomicLong clock = new AtomicLong();
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(clock::incrementAndGet);

        runConcurrently(recorder, i -> "shared#m:1:NEW");

        SiteRecord site = recorder.snapshot().get("shared#m:1:NEW");
        assertTrue(site.firstSeenMillis() <= site.lastSeenMillis(),
                () -> "first=" + site.firstSeenMillis() + " last=" + site.lastSeenMillis());
        assertTrue(site.firstSeenMillis() > 0, "first-seen must be a real observation, not the sentinel");
    }

    @Test
    @Timeout(60)
    void snapshotTakenDuringRecordingIsSelfConsistent() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(System::currentTimeMillis);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch stop = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> {
                while (stop.getCount() > 0) {
                    recorder.record("shared#m:1:NEW");
                }
            });
            for (int i = 0; i < 200; i++) {
                Map<String, SiteRecord> snapshot = recorder.snapshot();
                for (SiteRecord record : snapshot.values()) {
                    assertTrue(record.count() >= 0, "counts must never be observed negative");
                    assertTrue(record.firstSeenMillis() <= record.lastSeenMillis(),
                            () -> "inconsistent record observed mid-flight: " + record);
                }
            }
            stop.countDown();
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            stop.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void resetDuringRecordingDoesNotCorruptTheRecorder() throws Exception {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(System::currentTimeMillis);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch stop = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> {
                while (stop.getCount() > 0) {
                    recorder.record("shared#m:1:NEW");
                }
            });
            for (int i = 0; i < 100; i++) {
                recorder.reset();
                assertTrue(recorder.total() >= 0);
            }
            stop.countDown();
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            stop.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotIsAnImmutableCopyDetachedFromLaterRecording() {
        AllocationFlightRecorder recorder = new AllocationFlightRecorder(() -> 1L);
        recorder.record("A#m:1:NEW");
        Map<String, SiteRecord> snapshot = recorder.snapshot();

        recorder.record("A#m:1:NEW");
        recorder.record("B#m:1:NEW");

        assertEquals(1L, snapshot.get("A#m:1:NEW").count(), "a snapshot must not move under the caller");
        assertEquals(1, snapshot.size(), "nor gain sites recorded after it was taken");
        assertEquals(3L, recorder.total());
    }

    private interface KeyForThread {
        String apply(int threadIndex);
    }

    private static void runConcurrently(AllocationFlightRecorder recorder, KeyForThread key)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    go.await();
                    String siteKey = key.apply(index);
                    for (int i = 0; i < PER_THREAD; i++) {
                        recorder.record(siteKey);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
