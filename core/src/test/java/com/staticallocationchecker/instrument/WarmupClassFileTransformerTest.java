package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.NotWarmup;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import java.lang.instrument.ClassFileTransformer;
import java.util.ArrayList;
import java.util.List;
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
    void reportsWhyItDeclinedToTransformAClass() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        ClassFileTransformer reporting = new WarmupClassFileTransformer(diagnostics::add);

        reporting.transform(loader, "com/example/Corrupt", null, null, new byte[] {1, 2, 3, 4});

        assertEquals(1, diagnostics.size(),
                () -> "a silent failure is indistinguishable from success: " + diagnostics);
        assertTrue(diagnostics.get(0).contains("com/example/Corrupt"), diagnostics.get(0));
        assertTrue(diagnostics.get(0).contains("NOT being monitored"), diagnostics.get(0));
    }

    @Test
    void reportsEachDistinctFailureOnlyOnce() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        ClassFileTransformer reporting = new WarmupClassFileTransformer(diagnostics::add);

        for (int i = 0; i < 50; i++) {
            reporting.transform(loader, "com/example/Corrupt" + i, null, null, new byte[] {1, 2, 3, 4});
        }

        assertEquals(1, diagnostics.size(),
                () -> "a systemic failure must not flood the log once per class: " + diagnostics);
    }

    @Test
    void survivesADiagnosticSinkThatItselfThrows() {
        ClassFileTransformer reporting = new WarmupClassFileTransformer(message -> {
            throw new IllegalStateException("logging is broken");
        });

        assertDoesNotThrow(() -> reporting.transform(
                        loader, "com/example/Corrupt", null, null, new byte[] {1, 2, 3, 4}),
                "reporting must never become the reason a class fails to load");
    }
}
