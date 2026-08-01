package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.ArrayAllocations;
import com.staticallocationchecker.fixtures.Autoboxing;
import com.staticallocationchecker.fixtures.DirectNew;
import com.staticallocationchecker.fixtures.ExceptionAllocation;
import com.staticallocationchecker.fixtures.Lambdas;
import com.staticallocationchecker.fixtures.NoAllocation;
import com.staticallocationchecker.fixtures.RecursiveAllocation;
import com.staticallocationchecker.fixtures.StringConcatenation;
import com.staticallocationchecker.fixtures.TransitiveCaller;
import com.staticallocationchecker.fixtures.TypeLevelZeroAllocations;
import com.staticallocationchecker.fixtures.UnresolvableCall;
import com.staticallocationchecker.fixtures.WarmupBoundary;
import com.staticallocationchecker.fixtures.WarmupContract;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AllocationCheckerTest {

    private Report analyze() {
        return new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
    }

    @Test
    void flagsDirectNewInZeroAllocationsMethod() {
        List<Finding> findings = findingsFor(analyze(), DirectNew.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding finding = findings.get(0);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, finding.kind());
        assertEquals("make", finding.methodName());
        assertEquals(AllocationCategory.NEW, finding.category());
    }

    @Test
    void cleanZeroAllocationsMethodProducesNoFindings() {
        assertEquals(List.of(), findingsFor(analyze(), NoAllocation.class));
    }

    @Test
    void flagsArrayAllocations() {
        List<Finding> findings = findingsFor(analyze(), ArrayAllocations.class);

        assertEquals(3, findings.size(), () -> "expected three findings, got " + findings);
        assertTrue(findings.stream().allMatch(f -> f.category() == AllocationCategory.NEW_ARRAY),
                () -> "expected all NEW_ARRAY, got " + findings);
        assertTrue(findings.stream().allMatch(f -> f.kind() == Finding.Kind.ZERO_ALLOCATION_VIOLATION));
    }

    @Test
    void typeLevelAnnotationCascadesToEveryMethod() {
        List<Finding> findings = findingsFor(analyze(), TypeLevelZeroAllocations.class);

        assertEquals(
                Set.of("first", "second"),
                findings.stream().map(Finding::methodName).collect(Collectors.toSet()),
                () -> "expected first and second flagged, got " + findings);
    }

    @Test
    void flagsAllocationInTransitivelyCalledMethodWithCallPath() {
        List<Finding> findings = findingsFor(analyze(), TransitiveCaller.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding finding = findings.get(0);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, finding.kind());
        assertEquals("helper", finding.methodName(), "allocation site is the called method");
        assertEquals(2, finding.callPath().size(), () -> "expected entry -> helper, got " + finding.callPath());
        assertTrue(finding.callPath().get(0).contains("entry"), () -> "path starts at entry: " + finding.callPath());
        assertTrue(finding.callPath().get(1).contains("helper"), () -> "path ends at helper: " + finding.callPath());
    }

    @Test
    @Timeout(10)
    void recursiveCallGraphTerminatesAndReportsOnce() {
        List<Finding> findings = findingsFor(analyze(), RecursiveAllocation.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        assertEquals("recurse", findings.get(0).methodName());
    }

    @Test
    void reportsUnresolvableCallAsUnanalyzable() {
        List<Finding> findings = findingsFor(analyze(), UnresolvableCall.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding finding = findings.get(0);
        assertEquals(Finding.Kind.UNANALYZABLE_CALL, finding.kind());
        assertEquals("entry", finding.methodName());
        assertNull(finding.category());
        assertTrue(finding.callPath().get(finding.callPath().size() - 1).contains("length"),
                () -> "path should name the unresolved target: " + finding.callPath());
    }

    @Test
    void flagsAutoboxingAsBoxingAllocation() {
        List<Finding> findings = findingsFor(analyze(), Autoboxing.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding finding = findings.get(0);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, finding.kind());
        assertEquals(AllocationCategory.BOXING, finding.category());
    }

    @Test
    void flagsStringConcatenation() {
        List<Finding> findings = findingsFor(analyze(), StringConcatenation.class);

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        Finding finding = findings.get(0);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, finding.kind());
        assertEquals(AllocationCategory.STRING_CONCAT, finding.category());
    }

    @Test
    void flagsCapturingLambdaButNotStatelessLambda() {
        List<Finding> findings = findingsFor(analyze(), Lambdas.class);

        assertEquals(1, findings.size(), () -> "expected only the capturing lambda, got " + findings);
        Finding finding = findings.get(0);
        assertEquals("capturing", finding.methodName());
        assertEquals(AllocationCategory.LAMBDA, finding.category());
    }

    @Test
    void exemptsExceptionAllocations() {
        List<Finding> findings = findingsFor(analyze(), ExceptionAllocation.class);

        assertEquals(1, findings.size(),
                () -> "only the non-exception allocation should be flagged, got " + findings);
        assertEquals("allocatesObject", findings.get(0).methodName());
        assertEquals(AllocationCategory.NEW, findings.get(0).category());
    }

    @Test
    void doesNotFlagAllocationsBehindWarmupBoundary() {
        assertEquals(List.of(), findingsFor(analyze(), WarmupBoundary.class),
                "allocations reached through a warmup boundary are allowed");
    }

    @Test
    void enforcesWarmupContract() {
        Map<String, Finding.Kind> byMethod = findingsFor(analyze(), WarmupContract.class).stream()
                .collect(java.util.stream.Collectors.toMap(Finding::methodName, Finding::kind));

        assertEquals(Map.of(
                        "unconditional", Finding.Kind.WARMUP_NOT_GUARDED,
                        "discarded", Finding.Kind.WARMUP_NOT_CACHED),
                byMethod,
                "compliant() should be clean; the other two each violate one half of the contract");
    }
}
