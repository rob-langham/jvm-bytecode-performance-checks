package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.AnnotationSemantics;
import com.staticallocationchecker.fixtures.WarmupCaching;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The guarded-and-cached contract on {@code @AllocationsForWarmup} methods.
 *
 * <p>"Cached" is currently recognised only as a {@code PUTFIELD}/{@code PUTSTATIC} of the allocated
 * reference. The disabled tests below cover the other ways real warmup code retains an object.
 */
class WarmupContractTest {

    private Map<String, Finding.Kind> findingsByMethod(Class<?> fixture) {
        Report report = new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
        return findingsFor(report, fixture).stream()
                .collect(Collectors.toMap(Finding::methodName, Finding::kind, (a, b) -> a));
    }

    private List<Finding> findings(Class<?> fixture) {
        Report report = new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
        return findingsFor(report, fixture);
    }

    @Test
    void acceptsGuardedStoreIntoAStaticField() {
        assertFalseFlagged("staticField");
    }

    @Test
    void acceptsGuardedStoreThatPassesThroughALocalVariable() {
        assertFalseFlagged("throughLocal");
    }

    @Test
    void acceptsAllocationInsideATryBlock() {
        assertFalseFlagged("insideTryBlock");
    }

    @Test
    void acceptsTheArrayItselfWhenCachedInAField() {
        List<Finding> arrayFindings = findings(WarmupCaching.class).stream()
                .filter(f -> f.methodName().equals("intoArrayElements"))
                .toList();

        assertTrue(arrayFindings.stream().noneMatch(f -> f.category() == AllocationCategory.NEW_ARRAY),
                () -> "the pool array is stored straight into a field: " + arrayFindings);
    }

    @Test
    void acceptsBothAllocationsWhenAMethodHasSeveralCompliantSites() {
        assertFalseFlagged("twoCompliantAllocations");
    }

    @Test
    void acceptsAllocationGuardedByATernary() {
        assertFalseFlagged("guardedByTernary");
    }

    @Test
    void treatsALoopBodyAsGuarded() {
        assertEquals(null, findingsByMethod(WarmupCaching.class).get("guardedByLoop"),
                "a loop body may execute zero times, so the allocation is control-dependent");
    }

    @Test
    @Disabled("GAP: 'cached' means PUTFIELD/PUTSTATIC only, so an object retained by adding it to a "
            + "field-held collection is a false positive - object pools are a primary use case")
    void acceptsAllocationRetainedInAFieldHeldCollection() {
        assertFalseFlagged("intoCollection");
    }

    @Test
    @Disabled("GAP: as above, for Map.put")
    void acceptsAllocationRetainedInAFieldHeldMap() {
        assertFalseFlagged("intoMap");
    }

    @Test
    @Disabled("GAP: elements stored into a cached array go through AASTORE, not PUTFIELD, so "
            + "pre-populating a pool is reported as WARMUP_NOT_CACHED")
    void acceptsElementsStoredIntoACachedArray() {
        List<Finding> arrayFindings = findings(WarmupCaching.class).stream()
                .filter(f -> f.methodName().equals("intoArrayElements"))
                .toList();

        assertEquals(List.of(), arrayFindings, () -> "pool pre-population is warmup: " + arrayFindings);
    }

    @Test
    void appliesTheContractToEveryMethodOfATypeLevelAnnotatedClass() {
        Map<String, Finding.Kind> byMethod = findingsByMethod(AnnotationSemantics.TypeLevelWarmup.class);

        assertEquals(Map.of("unconditional", Finding.Kind.WARMUP_NOT_GUARDED), byMethod,
                "compliant() is clean; unconditional() is not");
    }

    @Test
    @Disabled("GAP: isWarmup is tested before the @ZeroAllocations branch, so when both annotations "
            + "are present the zero-allocation contract is silently ignored. Whichever precedence "
            + "is chosen should be deliberate and documented, and arguably this should be an error")
    void reportsAConflictWhenBothAnnotationsAreOnOneMethod() {
        List<Finding> conflict = findings(AnnotationSemantics.BothOnOneMethod.class);

        assertTrue(conflict.stream().anyMatch(f -> f.kind() == Finding.Kind.ZERO_ALLOCATION_VIOLATION),
                () -> "the stricter contract should not be silently dropped: " + conflict);
    }

    private void assertFalseFlagged(String methodName) {
        Map<String, Finding.Kind> byMethod = findingsByMethod(WarmupCaching.class);

        assertEquals(null, byMethod.get(methodName),
                () -> methodName + " is guarded and cached, but was flagged " + byMethod.get(methodName));
    }
}
