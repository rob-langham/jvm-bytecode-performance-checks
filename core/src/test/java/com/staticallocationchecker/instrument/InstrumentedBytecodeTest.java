package com.staticallocationchecker.instrument;

import static com.staticallocationchecker.instrument.Instrumentation.defineInstrumented;
import static com.staticallocationchecker.instrument.Instrumentation.originalBytes;
import static com.staticallocationchecker.instrument.Instrumentation.stripLineNumbers;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.ComplexControlFlow;
import com.staticallocationchecker.instrument.fixtures.TypeLevelWarmupClass;
import com.staticallocationchecker.instrument.fixtures.UninitializedAcrossBranch;
import com.staticallocationchecker.instrument.fixtures.WarmupWithoutAllocation;
import com.staticallocationchecker.TraceFixture;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That instrumented bytecode is still <em>valid</em> bytecode.
 *
 * <p>Every test here loads the rewritten class through a real classloader and invokes it, so the
 * JVM verifier runs against it. The instrumenter recomputes only {@code maxStack} on the assumption
 * that its inserted probe is stack-neutral and block-local; these are the shapes that would break
 * if that assumption ever stopped holding.
 */
class InstrumentedBytecodeTest {

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

    private Class<?> instrumentAndLoad(Class<?> fixture) throws Exception {
        byte[] instrumented = new AllocationInstrumenter(getClass().getClassLoader(), trace.siteCodes())
                .instrument(originalBytes(fixture));
        assertNotNull(instrumented, () -> fixture + " should have been instrumented");
        return defineInstrumented(fixture, instrumented);
    }

    @Test
    void instrumentedBranchesVerifyAndRun() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();
        Method branches = loaded.getMethod("branches", int.class);

        assertDoesNotThrow(() -> {
            branches.invoke(target, 20);
            branches.invoke(target, 7);
            branches.invoke(target, 1);
        });
        assertEquals(3L, trace.totalIn("branches"), "one allocation per branch taken");
    }

    @Test
    void aNewWhoseConstructorArgumentBranchesStillVerifies() throws Exception {
        // Before the fix this class failed to load: the probe took the offset that the frame's
        // Uninitialized(...) entry must give to the NEW, and the JVM threw ClassFormatError.
        Class<?> loaded = instrumentAndLoad(UninitializedAcrossBranch.class);
        Object target = loaded.getDeclaredConstructor().newInstance();
        Method make = loaded.getMethod("make", boolean.class);

        make.invoke(target, true);
        make.invoke(target, false);

        assertEquals(2L, trace.totalIn("make"), "one Holder per call, whichever branch the argument took");
    }

    @Test
    void aNewWithABranchingArgumentInsideALoopCountsEveryIteration() throws Exception {
        Class<?> loaded = instrumentAndLoad(UninitializedAcrossBranch.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        assertEquals(4, loaded.getMethod("loop", int.class).invoke(target, 4));
        assertEquals(4L, trace.totalIn("loop"), "the backward jump still lands on the probe");
    }

    @Test
    void instrumentedLoopsVerifyAndCountEveryIteration() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        loaded.getMethod("loop", int.class).invoke(target, 4);

        assertEquals(4L, trace.totalIn("loop"), "the loop body allocates once per iteration");
    }

    @Test
    void instrumentedExceptionHandlersVerifyAndRunOnBothPaths() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();
        Method method = loaded.getMethod("exceptionHandlers", boolean.class);

        assertDoesNotThrow(() -> {
            method.invoke(target, false);
            method.invoke(target, true);
        });
        assertTrue(trace.totalIn("exceptionHandlers") >= 4,
                () -> "both the normal and the caught path allocate, plus finally: " + trace.totalIn("exceptionHandlers"));
    }

    @Test
    void instrumentedSwitchVerifiesAndRuns() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();
        Method method = loaded.getMethod("switchStatement", int.class);

        assertDoesNotThrow(() -> {
            method.invoke(target, 0);
            method.invoke(target, 1);
            method.invoke(target, 99);
        });
        assertEquals(3L, trace.totalIn("switchStatement"));
    }

    @Test
    void instrumentedDeepExpressionStackVerifiesAndRuns() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        Object result = loaded.getMethod("deepStack", int.class, int.class).invoke(target, 2, 3);

        assertNotNull(result);
        assertTrue(trace.total() > 0, "the probe must survive insertion mid-expression");
    }

    @Test
    void recordsTheCapturingLambdaInstanceItself() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        loaded.getMethod("nestedLambda", StringBuilder.class).invoke(target, new StringBuilder());

        assertTrue(trace.total() > 0, "evaluating a capturing lambda allocates an instance");
    }

    @Test
    void instrumentsAllocationInsideALambdaBody() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        Runnable r = (Runnable) loaded.getMethod("nestedLambda", StringBuilder.class)
                .invoke(target, new StringBuilder());
        long afterCreation = trace.total();
        r.run();

        assertTrue(trace.total() > afterCreation,
                "the allocation inside the lambda body should be recorded when the body runs");
    }

    @Test
    void attributesALambdaBodyAllocationToTheSyntheticMethodThatContainsIt() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        Runnable r = (Runnable) loaded.getMethod("nestedLambda", StringBuilder.class)
                .invoke(target, new StringBuilder());
        r.run();

        assertTrue(trace.firedKeys().stream().anyMatch(k -> k.contains("#lambda$")),
                () -> "a lambda body's allocation belongs to the lambda, not its enclosing method, "
                        + "because the body can run long after warmup: " + trace.firedKeys());
    }

    @Test
    void recordsAnOrdinaryMethodReferencedByAWarmupMethodWhenItRuns() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        Supplier<?> supplier = (Supplier<?>) loaded.getMethod("viaMethodReference").invoke(target);
        long afterCreation = trace.total();
        supplier.get();

        assertTrue(trace.total() > afterCreation,
                "the referenced method is ordinary code: its own allocations are recorded when it runs");
    }

    @Test
    void instrumentsLambdaBodiesTransitively() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        Supplier<?> outer = (Supplier<?>) loaded.getMethod("nestedLambdaInLambda", StringBuilder.class)
                .invoke(target, new StringBuilder());
        Runnable inner = (Runnable) outer.get();
        long beforeBody = trace.total();
        inner.run();

        assertTrue(trace.total() > beforeBody,
                "an allocation two lambda levels deep is still inside the warmup method's work");
    }

    @Test
    void instrumentsEveryMethodOfATypeLevelAnnotatedClassIncludingInitialisers() throws Exception {
        Class<?> loaded = instrumentAndLoad(TypeLevelWarmupClass.class);
        Object target = loaded.getDeclaredConstructor().newInstance();
        loaded.getMethod("first").invoke(target);
        loaded.getMethod("second").invoke(target);

        Set<String> methods = trace.firedKeys().stream()
                .map(key -> key.substring(key.indexOf('#') + 1, key.indexOf(':')))
                .collect(Collectors.toSet());

        assertTrue(methods.containsAll(Set.of("first", "second", "<init>")),
                () -> "type-level annotation should reach every method: " + methods);
        assertTrue(methods.contains("<clinit>"),
                () -> "the static initialiser allocates too: " + methods);
    }

    @Test
    void leavesAWarmupMethodWithNoAllocationsUnchanged() {
        byte[] result = new AllocationInstrumenter(getClass().getClassLoader(), trace.siteCodes())
                .instrument(originalBytes(WarmupWithoutAllocation.class));

        assertNull(result, "nothing to probe means no rewrite, so no cost for the JIT to undo");
    }

    @Test
    void instrumentingTwiceDoublesTheProbesRatherThanCorruptingTheClass() throws Exception {
        AllocationInstrumenter instrumenter = new AllocationInstrumenter(getClass().getClassLoader(), trace.siteCodes());
        byte[] once = instrumenter.instrument(originalBytes(TypeLevelWarmupClass.class));
        byte[] twice = instrumenter.instrument(once);

        assertNotNull(twice, "an already-instrumented class still has allocation sites");
        Class<?> loaded = defineInstrumented(TypeLevelWarmupClass.class, twice);
        Object target = loaded.getDeclaredConstructor().newInstance();

        assertDoesNotThrow(() -> loaded.getMethod("first").invoke(target),
                "double instrumentation must still produce verifiable bytecode");
    }

    @Test
    void recordsSitesWhenTheClassCarriesNoLineNumberInformation() throws Exception {
        byte[] stripped = stripLineNumbers(originalBytes(TypeLevelWarmupClass.class));
        byte[] instrumented = new AllocationInstrumenter(getClass().getClassLoader(), trace.siteCodes()).instrument(stripped);
        Class<?> loaded = defineInstrumented(TypeLevelWarmupClass.class, instrumented);
        Object target = loaded.getDeclaredConstructor().newInstance();
        loaded.getMethod("first").invoke(target);

        assertTrue(trace.total() > 0, "instrumentation must not depend on debug information");
        assertTrue(trace.firedKeys().stream().allMatch(k -> k.contains(":-1@")),
                () -> "unknown lines are encoded as -1: " + trace.firedKeys());
    }

    @Test
void distinguishesTwoAllocationsOnTheSameSourceLine() throws Exception {
        Class<?> loaded = instrumentAndLoad(ComplexControlFlow.class);
        Object target = loaded.getDeclaredConstructor().newInstance();

        loaded.getMethod("sameLine", boolean.class, boolean.class).invoke(target, true, true);

        Map<String, Long> sites = trace.counts();
        long newSites = sites.keySet().stream()
                .filter(k -> k.contains("#sameLine:") && k.endsWith(":NEW")).count();
        assertEquals(2L, newSites, () -> "two distinct NEW sites in sameLine, got " + sites.keySet());
    }
}
