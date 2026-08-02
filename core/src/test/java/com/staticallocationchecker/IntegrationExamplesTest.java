package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks against realistic example code, driven by naming the entry point.
 *
 * <p>The unit fixtures each isolate one behaviour, which is what makes them good at pinning an edge
 * case and poor at showing how the tool behaves on code someone would actually write. These run the
 * real analyser over an order book, a matching engine and a buffer pool, and assert every finding
 * in full - kind, class, method and category, not a count, which would pass if two findings swapped
 * categories.
 *
 * <p>Naming the entry point rather than annotating the examples is what keeps them from turning up
 * in every other test's results, and exercises that capability at the same time.
 */
class IntegrationExamplesTest {

    private static final String EXAMPLES = "com.staticallocationchecker.examples.";

    /** Runs the checker from the named starting points and describes what came back. */
    private List<String> findingsFrom(String... entryPoints) {
        Report report = new AllocationChecker()
                .analyze(List.of(testClassesRoot()), List.of(), List.of(entryPoints));
        return report.findings().stream()
                .map(f -> f.kind()
                        + " | " + f.className().replace(EXAMPLES, "") + "#" + f.methodName()
                        + " | " + f.category())
                .sorted()
                .toList();
    }

    @Test
    void findsEveryAllocationCategoryOnAHotPath() {
        assertEquals(List.of(
                "UNANALYZABLE_CALL | OrderBook#lookup | null",
                "ZERO_ALLOCATION_VIOLATION | OrderBook#describe | STRING_CONCAT",
                "ZERO_ALLOCATION_VIOLATION | OrderBook#directNew | NEW",
                "ZERO_ALLOCATION_VIOLATION | OrderBook#lookup | BOXING",
                "ZERO_ALLOCATION_VIOLATION | OrderBook#onFill | LAMBDA",
                "ZERO_ALLOCATION_VIOLATION | OrderBook#scratchBuffer | NEW_ARRAY"),
                findingsFrom(
                        EXAMPLES + "OrderBook#directNew", EXAMPLES + "OrderBook#scratchBuffer",
                        EXAMPLES + "OrderBook#lookup", EXAMPLES + "OrderBook#describe",
                        EXAMPLES + "OrderBook#onFill", EXAMPLES + "OrderBook#noOp",
                        EXAMPLES + "OrderBook#reject"),
                "every category, plus the JDK call it cannot follow. noOp() and reject() are "
                        + "absent: a non-capturing lambda is a singleton, and Throwable is exempt");
    }

    @Test
    void reportsNothingForCodeWrittenToAllocateNothing() {
        assertEquals(List.of(), findingsFrom(
                        EXAMPLES + "MatchingEngine#lookup", EXAMPLES + "MatchingEngine#process",
                        EXAMPLES + "MatchingEngine#record"),
                "including process(), which is walked through its helper");
    }

    @Test
    void followsDispatchAndInheritanceToTheCodeThatAllocates() {
        assertEquals(List.of(
                "UNANALYZABLE_CALL | Handlers$BoxingHandler#handle | null",
                "ZERO_ALLOCATION_VIOLATION | Handlers$BaseProcessor#shared | NEW",
                "ZERO_ALLOCATION_VIOLATION | Handlers$BoxingHandler#handle | BOXING"),
                findingsFrom(EXAMPLES + "Handlers#dispatch", EXAMPLES + "Handlers#inherited"),
                "the findings name the implementation and the superclass, not the call sites");
    }

    @Test
    void attributesADispatchedFindingWithThePathThatReachedIt() {
        Report report = new AllocationChecker().analyze(
                List.of(testClassesRoot()), List.of(), List.of(EXAMPLES + "Handlers#dispatch"));

        Finding boxing = report.findings().stream()
                .filter(f -> f.category() == AllocationCategory.BOXING)
                .findFirst()
                .orElseThrow();

        assertEquals(2, boxing.callPath().size(), () -> "got " + boxing.callPath());
        assertTrue(boxing.callPath().get(0).contains("Handlers#dispatch"), boxing.callPath().toString());
        assertTrue(boxing.callPath().get(1).contains("BoxingHandler#handle"), boxing.callPath().toString());
    }

    @Test
    void distinguishesASynthesisedVarargsArrayFromOneWrittenByHand() {
        assertEquals(List.of(
                "ZERO_ALLOCATION_VIOLATION | Telemetry#publish | VARARGS_ARRAY",
                "ZERO_ALLOCATION_VIOLATION | Telemetry#publishExplicitArray | NEW_ARRAY"),
                findingsFrom(
                        EXAMPLES + "Telemetry#publish", EXAMPLES + "Telemetry#publishExplicitArray",
                        EXAMPLES + "Telemetry#publishExisting",
                        EXAMPLES + "Telemetry#publishViaOverload"),
                "identical bytecode; only the callee's ACC_VARARGS flag tells them apart. "
                        + "publishExisting() and publishViaOverload() allocate nothing");
    }

    @Test
    void enforcesTheWarmupContractAndAllowsCompliantLazyInit() {
        assertEquals(List.of(
                "WARMUP_NOT_CACHED | BufferPool#uncached | NEW_ARRAY",
                "WARMUP_NOT_GUARDED | BufferPool#unguarded | NEW_ARRAY"),
                findingsFrom(
                        EXAMPLES + "BufferPool#buffer", EXAMPLES + "BufferPool#prefill",
                        EXAMPLES + "BufferPool#unguarded", EXAMPLES + "BufferPool#uncached"),
                "buffer() and prefill() are guarded and cached, so they are allowed");
    }

    @Test
    void aHotPathCallingAWarmupMethodStopsAtTheBoundary() {
        assertEquals(List.of(), findingsFrom(EXAMPLES + "BufferPool#hotPath"),
                "the warmup method's allocations are sanctioned, so the walk does not descend");
    }

    @Test
    void aSingleOverloadCanBeTargetedByDescriptor() {
        assertEquals(List.of("ZERO_ALLOCATION_VIOLATION | OrderBook#scratchBuffer | NEW_ARRAY"),
                findingsFrom(EXAMPLES + "OrderBook#scratchBuffer(I)[J"));
    }

    @Test
    void namingAnEntryPointSkipsAnnotationDiscoveryEntirely() {
        List<String> targeted = findingsFrom(EXAMPLES + "MatchingEngine#lookup");

        assertEquals(List.of(), targeted,
                "the test tree is full of annotated fixtures; naming a starting point must "
                        + "analyse that and nothing else");
    }

    @Test
    void aClassLevelEntryPointIncludesConstructionAndSyntheticMethods() {
        List<String> wholeClass = findingsFrom(EXAMPLES + "MatchingEngine");

        assertTrue(wholeClass.stream().allMatch(f -> f.contains("#<init>")),
                () -> "naming a class means every method it declares, construction included - "
                        + "which is usually not the hot path, and is why the tests above name "
                        + "methods instead: " + wholeClass);
        assertEquals(3, wholeClass.size(),
                "the three field arrays, allocated once at construction");
    }

    @Test
    void anEntryPointThatMatchesNothingIsAnError() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> findingsFrom("com.example.TypoedClassName"));

        assertTrue(thrown.getMessage().contains("matched nothing"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("indistinguishable"),
                "a typo must not read as a clean result: " + thrown.getMessage());
    }
}
