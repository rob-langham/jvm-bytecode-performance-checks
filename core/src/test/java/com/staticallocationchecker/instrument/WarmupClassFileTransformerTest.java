package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.NotWarmup;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import java.lang.instrument.ClassFileTransformer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The transformer's contract with the JVM: never throw, return null when unchanged, and survive
 * being handed input it cannot make sense of.
 */
class WarmupClassFileTransformerTest {

    private final ClassFileTransformer transformer = new WarmupClassFileTransformer();
    private final ClassLoader loader = getClass().getClassLoader();

    private byte[] transform(String internalName, byte[] bytes) {
        try {
            return transformer.transform(loader, internalName, null, null, bytes);
        } catch (java.lang.instrument.IllegalClassFormatException e) {
            throw new AssertionError("the transformer must never signal failure to the JVM", e);
        }
    }

    @Test
    void rewritesAWarmupClass() {
        byte[] result = transform(
                "com/staticallocationchecker/instrument/fixtures/WarmupTarget",
                originalBytes(WarmupTarget.class));

        assertNotNull(result);
    }

    @Test
    void returnsNullForAClassWithNoWarmupMethods() {
        assertNull(transform(
                "com/staticallocationchecker/instrument/fixtures/NotWarmup",
                originalBytes(NotWarmup.class)));
    }

    @Test
    void neverThrowsOnMalformedBytecode() {
        assertDoesNotThrow(() -> transform("com/example/Corrupt", new byte[] {1, 2, 3, 4}),
                "a throwing transformer would abort class loading for the whole application");
        assertNull(transform("com/example/Corrupt", new byte[] {1, 2, 3, 4}));
    }

    @Test
    void neverThrowsOnEmptyInput() {
        assertDoesNotThrow(() -> transform("com/example/Empty", new byte[0]));
        assertNull(transform("com/example/Empty", new byte[0]));
    }

    @Test
    void toleratesABootstrapClassLoaderWithoutThrowing() {
        assertDoesNotThrow(() -> transformer.transform(
                null, "com/example/Bootstrapped", null, null, originalBytes(WarmupTarget.class)),
                "loader is null for bootstrap classes; the transformer must cope");
    }

    @Test
    @Disabled("GAP: the transformer degrades to 'no transformation' on any Throwable, with no "
            + "signal of any kind. That is how the agent's total failure to load ASM in a real "
            + "launch stays invisible: every class silently declines to be instrumented. It needs "
            + "at least a one-line warning, ideally once per distinct failure")
    void reportsWhyItDeclinedToTransformAClass() {
        StringBuilder diagnostics = new StringBuilder();

        transform("com/example/Corrupt", new byte[] {1, 2, 3, 4});

        assertTrue(diagnostics.length() > 0,
                "a silent failure in a verification tool is indistinguishable from success");
    }
}
