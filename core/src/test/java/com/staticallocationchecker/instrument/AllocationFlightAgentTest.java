package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.TraceFixture;
import com.staticallocationchecker.instrument.fixtures.WarmupWithoutAllocation;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import com.staticallocationchecker.runtime.AllocationTrace;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllocationFlightAgentTest {

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

    @Test
    void transformerInstrumentsAnyClassThatAllocates() {
        AllocationClassFileTransformer transformer = new AllocationClassFileTransformer(trace.trace());
        ClassLoader loader = getClass().getClassLoader();

        byte[] warmup = transformer.transform(loader,
                "demo/WarmupTarget",
                null, null, originalBytes(WarmupTarget.class));
        byte[] plain = transformer.transform(loader,
                "demo/WarmupWithoutAllocation",
                null, null, originalBytes(WarmupWithoutAllocation.class));

        assertNotNull(warmup, "a class with allocation sites should be instrumented");
        assertNull(plain, "a class with no allocation sites should be returned unchanged (null)");
    }

    @Test
    void premainInstallsOneTransformerAndOpensTheTraceInTheGivenDirectory() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        try {
            AllocationFlightAgent.premain("dir=" + dir.resolve("run"), fakeInstrumentation(added));

            assertEquals(1, added.size(), "premain should install one transformer");
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("run").resolve(AllocationTrace.EVENTS_FILE)),
                    "the events file should be created in the directory the agent was given");
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("run").resolve(AllocationTrace.KEYS_FILE)),
                    "the keys file should be created in the directory the agent was given");
        } finally {
            AllocationTrace.instance().close();
            AllocationTrace.install(null);
        }
    }

    @Test
    void agentmainInstallsTheSameMachineryAsPremain() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        try {
            AllocationFlightAgent.agentmain("dir=" + dir.resolve("attached"), fakeInstrumentation(added));

            assertEquals(1, added.size(), "dynamic attach should install the transformer too");
        } finally {
            AllocationTrace.instance().close();
            AllocationTrace.install(null);
        }
    }

    @Test
    void directoryIsTheDirOptionOrTheDefault() {
        assertEquals(Paths.get("out/run"), AllocationFlightAgent.directoryFrom("x=1,dir=out/run"));
        assertEquals(Paths.get(AllocationFlightAgent.DEFAULT_DIRECTORY), AllocationFlightAgent.directoryFrom(null));
        assertEquals(Paths.get(AllocationFlightAgent.DEFAULT_DIRECTORY), AllocationFlightAgent.directoryFrom("someOption=1"));
    }

    @Test
    void warmupIsTheWarmupOptionInSecondsAndZeroWhenAbsent() {
        assertEquals(500L, AllocationFlightAgent.warmupMillisFrom("dir=out,warmup=0.5"));
        assertEquals(2_000L, AllocationFlightAgent.warmupMillisFrom("warmup=2"));
        assertEquals(0L, AllocationFlightAgent.warmupMillisFrom("dir=out"));
        assertEquals(0L, AllocationFlightAgent.warmupMillisFrom(null));
        assertThrows(IllegalArgumentException.class, () -> AllocationFlightAgent.warmupMillisFrom("warmup=-1"));
    }

    @Test
    void theWarmUpOptionDoesNotDisturbTheDirectory() {
        assertEquals(Paths.get("out/run"), AllocationFlightAgent.directoryFrom("warmup=3,dir=out/run"));
    }

    @Test
    void agentmainRetransformsAlreadyLoadedApplicationClasses() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        List<String> retransformed = new ArrayList<>();
        try {
            AllocationFlightAgent.agentmain("dir=" + dir.resolve("attached"),
                    recordingInstrumentation(added, retransformed));

            assertFalse(retransformed.isEmpty(),
                    "already-loaded application classes should be retransformed on attach");
        } finally {
            AllocationTrace.instance().close();
            AllocationTrace.install(null);
        }
    }

    /** An {@code Instrumentation} that reports one already-loaded application class. */
    private java.lang.instrument.Instrumentation recordingInstrumentation(
            List<ClassFileTransformer> added, List<String> retransformed) {
        return (java.lang.instrument.Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addTransformer":
                            added.add((ClassFileTransformer) args[0]);
                            return null;
                        case "getAllLoadedClasses":
                            return new Class<?>[] {org.junit.jupiter.api.Assertions.class};
                        case "isModifiableClass":
                        case "isRetransformClassesSupported":
                            return true;
                        case "retransformClasses":
                            // Varargs: the proxy receives the array, even for a single class.
                            for (Class<?> c : (Class<?>[]) args[0]) {
                                retransformed.add(c.getName());
                            }
                            return null;
                        default:
                            Class<?> returnType = method.getReturnType();
                            if (returnType == boolean.class) {
                                return false;
                            }
                            return returnType.isPrimitive() ? 0 : null;
                    }
                });
    }

    private java.lang.instrument.Instrumentation fakeInstrumentation(List<ClassFileTransformer> added) {
        return (java.lang.instrument.Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.lang.instrument.Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addTransformer")) {
                        added.add((ClassFileTransformer) args[0]);
                        return null;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType.isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }
}
