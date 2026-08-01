package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.defineInstrumented;
import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.EagerWarmup;
import com.staticallocationchecker.instrument.fixtures.MixedWarmup;
import com.staticallocationchecker.instrument.fixtures.NotWarmup;
import com.staticallocationchecker.instrument.fixtures.WarmupKinds;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import com.staticallocationchecker.runtime.AllocationFlightRecorder;
import com.staticallocationchecker.runtime.SiteRecord;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarmupInstrumenterTest {

    private AllocationFlightRecorder recorder;

    @BeforeEach
    void installRecorder() {
        recorder = new AllocationFlightRecorder(() -> 1_000L);
        AllocationFlightRecorder.install(recorder);
    }

    private Object instrumentAndInvoke(Class<?> fixture, String method) throws Exception {
        byte[] instrumented = new WarmupInstrumenter(getClass().getClassLoader())
                .instrument(originalBytes(fixture));
        Class<?> loaded = defineInstrumented(fixture, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();
        return loaded.getMethod(method).invoke(target);
    }

    @Test
    void recordsAllocationInWarmupMethod() throws Exception {
        instrumentAndInvoke(WarmupTarget.class, "warm");

        assertEquals(1L, recorder.total());
        Map<String, SiteRecord> sites = recorder.snapshot();
        String key = sites.keySet().iterator().next();
        assertTrue(key.startsWith("com.staticallocationchecker.instrument.fixtures.WarmupTarget#warm:"),
                () -> "unexpected site key: " + key);
        assertTrue(key.endsWith(":NEW"), () -> "unexpected category in key: " + key);
        assertEquals(1L, sites.get(key).count());
    }

    @Test
    void leavesClassesWithoutWarmupMethodsUnchanged() {
        byte[] result = new WarmupInstrumenter(getClass().getClassLoader())
                .instrument(originalBytes(NotWarmup.class));
        assertNull(result, "a class with no warmup methods should not be rewritten");
    }

    @Test
    void doesNotInstrumentNonWarmupMethods() throws Exception {
        instrumentAndInvoke(MixedWarmup.class, "hot");

        assertEquals(0L, recorder.total(), "allocations in ordinary methods must not be recorded");
    }

    @Test
    void recordsEveryAllocationCategoryButNotExceptions() throws Exception {
        byte[] instrumented = new WarmupInstrumenter(getClass().getClassLoader())
                .instrument(originalBytes(WarmupKinds.class));
        Class<?> loaded = defineInstrumented(WarmupKinds.class, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();
        loaded.getMethod("warm", int.class, StringBuilder.class).invoke(target, 3, new StringBuilder());

        Set<String> categories = recorder.snapshot().keySet().stream()
                .map(key -> key.substring(key.lastIndexOf(':') + 1))
                .collect(Collectors.toSet());

        assertEquals(Set.of("NEW_ARRAY", "BOXING", "STRING_CONCAT", "LAMBDA"), categories,
                "all categories recorded; exempt exception allocation excluded");
    }

    @Test
    void accumulatesCountAcrossInvocations() throws Exception {
        byte[] instrumented = new WarmupInstrumenter(getClass().getClassLoader())
                .instrument(originalBytes(EagerWarmup.class));
        Class<?> loaded = defineInstrumented(EagerWarmup.class, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();

        loaded.getMethod("make").invoke(target);
        loaded.getMethod("make").invoke(target);

        assertEquals(2L, recorder.total());
        String key = recorder.snapshot().keySet().iterator().next();
        assertEquals(2L, recorder.snapshot().get(key).count());
    }
}
