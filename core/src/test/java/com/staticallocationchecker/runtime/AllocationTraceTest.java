package com.staticallocationchecker.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllocationTraceTest {

    @TempDir
    Path dir;

    private static List<long[]> readEvents(Path dir) throws IOException {
        List<long[]> events = new ArrayList<>();
        AllocationTraceReader.forEachEvent(dir,
                (code, nanos) -> events.add(new long[] {code, nanos}));
        return events;
    }

    @Test
    void eachEventIsAFixedTwelveByteRecordOfCodeThenTimestamp() throws IOException {
        AllocationTrace trace = AllocationTrace.open(dir, () -> 0x0102030405060708L);
        trace.record(7);
        trace.close();

        byte[] bytes = Files.readAllBytes(dir.resolve(AllocationTrace.EVENTS_FILE));
        assertEquals(AllocationTrace.RECORD_BYTES, bytes.length, "one event is one 12-byte record");
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[1]);
        assertEquals(0, bytes[2]);
        assertEquals(7, bytes[3], "code is a big-endian int");
        assertEquals(0x01, bytes[4], "timestamp is a big-endian long, most significant byte first");
        assertEquals(0x08, bytes[11]);
    }

    @Test
    void eventsComeBackInOrderWithTheirCodesAndTimestamps() throws IOException {
        AtomicLong now = new AtomicLong(100);
        AllocationTrace trace = AllocationTrace.open(dir, now::incrementAndGet);
        trace.record(3);
        trace.record(3);
        trace.record(9);
        trace.close();

        List<long[]> events = readEvents(dir);
        assertEquals(3, events.size());
        assertEquals(3, events.get(0)[0]);
        assertEquals(101, events.get(0)[1]);
        assertEquals(9, events.get(2)[0]);
        assertEquals(103, events.get(2)[1]);
    }

    @Test
    void siteCodesAreAssignedOnceAndWrittenToTheKeyFile() throws IOException {
        AllocationTrace trace = AllocationTrace.open(dir, () -> 0L);
        int first = trace.siteCode("demo.A#run:10@4:NEW");
        int second = trace.siteCode("demo.B#run:12@8:BOXING");
        int again = trace.siteCode("demo.A#run:10@4:NEW");
        trace.close();

        assertEquals(0, first);
        assertEquals(1, second);
        assertEquals(first, again, "the same key always gets the same code");

        Map<Integer, String> keys = AllocationTraceReader.readKeys(dir);
        assertEquals(Map.of(0, "demo.A#run:10@4:NEW", 1, "demo.B#run:12@8:BOXING"), keys);
    }

    @Test
    void manyThreadsLoseNoEvents() throws Exception {
        AllocationTrace trace = AllocationTrace.open(dir, () -> 1L);
        int threads = 4;
        int perThread = 50_000;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int code = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    trace.record(code);
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        trace.close();

        long[] perCode = new long[threads];
        AllocationTraceReader.forEachEvent(dir, (code, nanos) -> perCode[code]++);
        for (long count : perCode) {
            assertEquals(perThread, count, "every event from every thread must be written");
        }
    }

    @Test
    void closeWritesBufferedEventsAndLaterRecordsAreDroppedNotThrown() throws IOException {
        AllocationTrace trace = AllocationTrace.open(dir, () -> 5L);
        trace.record(1);
        trace.close();

        assertDoesNotThrow(() -> trace.record(2), "recording after close must never throw into the application");
        assertEquals(1, readEvents(dir).size(), "only the event recorded before close is kept");
    }

    @Test
    void theStaticProbeIsASafeNoOpWithNoTraceInstalled() {
        AllocationTrace previous = AllocationTrace.instance();
        AllocationTrace.install(null);
        try {
            assertDoesNotThrow(() -> AllocationTrace.recordSite(42));
        } finally {
            AllocationTrace.install(previous);
        }
    }

    @Test
    void theStaticProbeRecordsIntoTheInstalledTrace() throws IOException {
        AllocationTrace previous = AllocationTrace.instance();
        AllocationTrace trace = AllocationTrace.open(dir, () -> 9L);
        AllocationTrace.install(trace);
        try {
            AllocationTrace.recordSite(4);
        } finally {
            AllocationTrace.install(previous);
            trace.close();
        }

        List<long[]> events = readEvents(dir);
        assertEquals(1, events.size());
        assertEquals(4, events.get(0)[0]);
    }

    @Test
    void readerIgnoresAPartialTrailingRecord() throws IOException {
        AllocationTrace trace = AllocationTrace.open(dir, () -> 2L);
        trace.record(6);
        trace.close();
        byte[] whole = Files.readAllBytes(dir.resolve(AllocationTrace.EVENTS_FILE));
        byte[] torn = new byte[whole.length + 5];
        System.arraycopy(whole, 0, torn, 0, whole.length);
        Files.write(dir.resolve(AllocationTrace.EVENTS_FILE), torn);

        List<long[]> events = readEvents(dir);
        assertEquals(1, events.size(), "a process killed mid-write leaves a torn tail; keep what is whole");
        assertEquals(6, events.get(0)[0]);
    }
}
