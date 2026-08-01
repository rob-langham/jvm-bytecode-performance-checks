package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
