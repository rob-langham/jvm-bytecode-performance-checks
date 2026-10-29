package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.defineInstrumented;
import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.TraceFixture;
import com.staticallocationchecker.instrument.fixtures.EagerWarmup;
import com.staticallocationchecker.instrument.fixtures.MixedWarmup;
import com.staticallocationchecker.instrument.fixtures.WarmupWithoutAllocation;
import com.staticallocationchecker.instrument.fixtures.WarmupKinds;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllocationInstrumenterTest {

    @TempDir
    Path dir;

    private TraceFixture trace;

    @BeforeEach
    void installTrace() throws Exception {
        trace = new TraceFixture(dir);
    }

    @AfterEach
    void closeTrace() throws Exception {
        trace.close();
    }

    private AllocationInstrumenter instrumenter() {
        return new AllocationInstrumenter(getClass().getClassLoader(), trace.siteCodes());
    }

    private Object instrumentAndInvoke(Class<?> fixture, String method) throws Exception {
        byte[] instrumented = instrumenter().instrument(originalBytes(fixture));
        Class<?> loaded = defineInstrumented(fixture, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();
        return loaded.getMethod(method).invoke(target);
    }

    @Test
    void recordsAllocationInWarmupMethod() throws Exception {
        instrumentAndInvoke(WarmupTarget.class, "warm");

        Map<String, Long> counts = trace.counts();
        assertEquals(1, counts.size(), () -> "one site should have fired: " + counts);
        String key = counts.keySet().iterator().next();
        assertTrue(key.startsWith("com.staticallocationchecker.instrument.fixtures.WarmupTarget#warm:"),
                () -> "unexpected site key: " + key);
        assertTrue(key.endsWith(":NEW"), () -> "unexpected category in key: " + key);
        assertEquals(1L, counts.get(key));
    }

    @Test
    void leavesClassesWithoutAllocationSitesUnchanged() {
        byte[] result = instrumenter().instrument(originalBytes(WarmupWithoutAllocation.class));
        assertNull(result, "a class with no allocation sites should not be rewritten");
    }

    @Test
    void recordsAllocationsInOrdinaryMethodsAsWellAsWarmupMethods() throws Exception {
        instrumentAndInvoke(MixedWarmup.class, "hot");

        assertEquals(1L, trace.total(), "allocations in ordinary methods are recorded too");
        assertTrue(trace.firedKeys().iterator().next().contains("#hot:"),
                () -> "the site should name the ordinary method: " + trace.firedKeys());
    }

    @Test
    void recordsEveryAllocationCategoryButNotExceptions() throws Exception {
        byte[] instrumented = instrumenter().instrument(originalBytes(WarmupKinds.class));
        Class<?> loaded = defineInstrumented(WarmupKinds.class, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();
        loaded.getMethod("warm", int.class, StringBuilder.class).invoke(target, 3, new StringBuilder());

        Set<String> categories = trace.firedKeys().stream()
                .map(key -> key.substring(key.lastIndexOf(':') + 1))
                .collect(Collectors.toSet());

        assertEquals(Set.of("NEW_ARRAY", "BOXING", "STRING_CONCAT", "LAMBDA"), categories,
                "all categories recorded; exempt exception allocation excluded");
    }

    @Test
    void accumulatesEventsAcrossInvocations() throws Exception {
        byte[] instrumented = instrumenter().instrument(originalBytes(EagerWarmup.class));
        Class<?> loaded = defineInstrumented(EagerWarmup.class, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();

        loaded.getMethod("make").invoke(target);
        loaded.getMethod("make").invoke(target);

        assertEquals(2L, trace.total());
        Map<String, Long> counts = trace.counts();
        assertEquals(2L, counts.values().iterator().next().longValue());
    }

    @Test
    void theSameSiteKeyAlwaysGetsTheSameCode() throws Exception {
        instrumenter().instrument(originalBytes(WarmupTarget.class));
        Set<String> first = trace.assignedKeys();

        instrumenter().instrument(originalBytes(WarmupTarget.class));

        assertEquals(first, trace.assignedKeys(),
                "re-instrumenting (as retransformation does) must not invent new codes");
    }
}
