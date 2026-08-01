package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.NotWarmup;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import com.staticallocationchecker.runtime.AllocationFlightRecorder;
import java.lang.instrument.ClassFileTransformer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.management.ObjectName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class AllocationFlightAgentTest {

    @Test
    void transformerInstrumentsOnlyWarmupClasses() {
        WarmupClassFileTransformer transformer = new WarmupClassFileTransformer();
        ClassLoader loader = getClass().getClassLoader();

        byte[] warmup = transformer.transform(loader,
                "com/staticallocationchecker/instrument/fixtures/WarmupTarget",
                null, null, originalBytes(WarmupTarget.class));
        byte[] plain = transformer.transform(loader,
                "com/staticallocationchecker/instrument/fixtures/NotWarmup",
                null, null, originalBytes(NotWarmup.class));

        assertNotNull(warmup, "warmup class should be instrumented");
        assertNull(plain, "non-warmup class should be returned unchanged (null)");
    }

    @Test
    void premainRegistersTransformerAndMBean() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        java.lang.instrument.Instrumentation inst = fakeInstrumentation(added);

        try {
            AllocationFlightAgent.premain("", inst);

            assertEquals(1, added.size(), "premain should install one transformer");
            assertTrue(ManagementFactory.getPlatformMBeanServer()
                    .isRegistered(new ObjectName(AllocationFlightRecorder.OBJECT_NAME)), "MBean registered");
        } finally {
            AllocationFlightRecorder.unregister();
        }
    }

    @Test
    void agentmainInstallsTheSameMachineryAsPremain() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();

        try {
            AllocationFlightAgent.agentmain("", fakeInstrumentation(added));

            assertEquals(1, added.size(), "dynamic attach should install the transformer too");
            assertTrue(ManagementFactory.getPlatformMBeanServer()
                    .isRegistered(new ObjectName(AllocationFlightRecorder.OBJECT_NAME)), "MBean registered");
        } finally {
            AllocationFlightRecorder.unregister();
        }
    }

    @Test
    void premainToleratesNullAndNonEmptyArgumentStrings() {
        List<ClassFileTransformer> added = new ArrayList<>();
        try {
            AllocationFlightAgent.premain(null, fakeInstrumentation(added));
            AllocationFlightAgent.premain("someOption=1", fakeInstrumentation(added));

            assertEquals(2, added.size());
        } finally {
            AllocationFlightRecorder.unregister();
        }
    }

    @Test
    @Disabled("GAP: the jar declares Can-Retransform-Classes and an Agent-Class, but install() only "
            + "calls addTransformer - it never calls retransformClasses. Attaching to a running JVM "
            + "therefore instruments nothing already loaded, which in a warmup-monitoring tool is "
            + "precisely the classes you attached in order to observe")
    void agentmainRetransformsAlreadyLoadedWarmupClasses() throws Exception {
        List<ClassFileTransformer> added = new ArrayList<>();
        List<String> retransformed = new ArrayList<>();
        java.lang.instrument.Instrumentation inst = recordingInstrumentation(added, retransformed);

        try {
            AllocationFlightAgent.agentmain("", inst);

            assertFalse(retransformed.isEmpty(),
                    "already-loaded warmup classes should be retransformed on attach");
        } finally {
            AllocationFlightRecorder.unregister();
        }
    }

    /** An {@code Instrumentation} that reports one already-loaded warmup class. */
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
                            return new Class<?>[] {WarmupTarget.class};
                        case "isModifiableClass":
                        case "isRetransformClassesSupported":
                            return true;
                        case "retransformClasses":
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
