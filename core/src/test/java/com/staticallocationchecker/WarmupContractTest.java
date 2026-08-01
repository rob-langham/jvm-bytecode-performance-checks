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
 * <p>"Cached" means the allocated reference is retained past the method: stored into a field,
 * written into an array, or handed to a collection already reachable from a field.
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
    void acceptsAllocationRetainedInAFieldHeldCollection() {
        assertFalseFlagged("intoCollection");
    }

    @Test
    void acceptsAllocationRetainedInAFieldHeldMap() {
        assertFalseFlagged("intoMap");
    }

    @Test
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
void reportsAConflictWhenBothAnnotationsAreOnOneMethod() {
        List<Finding> conflict = findings(AnnotationSemantics.BothOnOneMethod.class);

        assertEquals(1, conflict.size(), () -> "expected one conflict finding, got " + conflict);
        assertEquals(Finding.Kind.CONFLICTING_CONTRACTS, conflict.get(0).kind(),
                "the two contracts contradict each other; picking one silently hides a mistake");
        assertEquals("entry", conflict.get(0).methodName());
    }

    @Test
    void allowsAWarmupMethodInsideAZeroAllocationType() {
        assertEquals(List.of(), findings(AnnotationSemantics.WarmupMethodInZeroAllocationType.class),
                "a type-level contract with a more specific method-level one is not a conflict");
    }

    private void assertFalseFlagged(String methodName) {
        Map<String, Finding.Kind> byMethod = findingsByMethod(WarmupCaching.class);

        assertEquals(null, byMethod.get(methodName),
                () -> methodName + " is guarded and cached, but was flagged " + byMethod.get(methodName));
    }
}
