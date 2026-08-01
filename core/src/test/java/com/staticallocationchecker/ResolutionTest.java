package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.Dispatch;
import com.staticallocationchecker.fixtures.Inheritance;
import com.staticallocationchecker.fixtures.Varargs;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * How call sites are resolved to the bytecode that actually runs.
 *
 * <p>Several tests here are {@link Disabled}: they state the behaviour the checker should have, and
 * currently fail. Each names the gap it is waiting on. They are the specification for that work,
 * not a record of what the tool does today.
 */
class ResolutionTest {

    private Report analyze() {
        return new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
    }

    @Test
    void findsAllocationInAnInterfaceImplementation() {
        List<Finding> findings = findingsFor(analyze(), Dispatch.AllocatingHandler.class);

        assertEquals(1, findings.size(), () -> "expected the override's allocation, got " + findings);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, findings.get(0).kind());
        assertEquals("handle", findings.get(0).methodName());
    }

    @Test
    void findsAllocationInAnAbstractClassOverride() {
        List<Finding> findings = findingsFor(analyze(), Dispatch.Impl.class);

        assertEquals(1, findings.size(), () -> "expected the override's allocation, got " + findings);
        assertEquals("make", findings.get(0).methodName());
    }

    @Test
    void attributesADispatchedAllocationToTheOverrideNotTheCallSite() {
        List<Finding> findings = findingsFor(analyze(), Dispatch.class);

        assertTrue(findings.stream().noneMatch(f -> f.methodName().equals("throughInterface")),
                () -> "the call site itself allocates nothing: " + findings);
        assertTrue(findings.stream().noneMatch(f -> f.methodName().equals("throughAbstractClass")),
                () -> "the call site itself allocates nothing: " + findings);
    }

    @Test
    void reportsUnanalyzableWhenNoImplementationIsIndexed() {
        List<Finding> findings = findingsFor(analyze(), Dispatch.class).stream()
                .filter(f -> f.methodName().equals("throughUnindexedInterface"))
                .toList();

        assertEquals(1, findings.size(), () -> "expected one finding, got " + findings);
        assertEquals(Finding.Kind.UNANALYZABLE_CALL, findings.get(0).kind(),
                "an implementation outside the roots must be flagged, never assumed clean");
    }

    @Test
    void walksEveryIndexedOverrideOfADispatchedCall() {
        Report report = analyze();

        assertEquals(1, findingsFor(report, Dispatch.AllocatingHandler.class).size());
        assertEquals(1, findingsFor(report, Dispatch.Impl.class).size());
    }

    @Test
    void findsAllocationInAnInheritedMethod() {
        List<Finding> findings = findingsFor(analyze(), Inheritance.AllocatingParent.class);

        assertEquals(1, findings.size(), () -> "expected the parent's allocation, got " + findings);
        assertEquals("inherited", findings.get(0).methodName());
    }

    @Test
    void doesNotReportInheritedCleanMethodAsUnanalyzable() {
        List<Finding> findings = findingsFor(analyze(), Inheritance.class);

        assertEquals(List.of(), findings,
                () -> "both inherited calls resolve within the analysis roots, got " + findings);
    }

    @Test
    void flagsTheImplicitArrayAtAVarargsCallSite() {
        List<Finding> findings = findingsFor(analyze(), Varargs.class);

        assertEquals(2, findings.size(),
                () -> "the two synthesised arrays, but not the pre-existing array, got " + findings);
        assertTrue(findings.stream().noneMatch(f -> f.methodName().equals("passesExistingArray")),
                () -> "passing an existing array allocates nothing: " + findings);
    }

    @Test
    @Disabled("GAP: AllocationCategory.VARARGS_ARRAY is declared but never produced - a varargs "
            + "call site is reported as the less specific NEW_ARRAY")
    void categorisesVarargsArraysDistinctlyFromExplicitArrays() {
        List<Finding> findings = findingsFor(analyze(), Varargs.class);

        assertTrue(findings.stream().allMatch(f -> f.category() == AllocationCategory.VARARGS_ARRAY),
                () -> "expected VARARGS_ARRAY, got " + findings);
    }

    @Test
    void reportsAnAllocationOncePerDistinctCallPath() {
        List<Finding> findings = findingsFor(analyze(), com.staticallocationchecker.fixtures.SharedHelper.class);

        assertEquals(2, findings.size(),
                () -> "the shared helper is reachable from two entry points, got " + findings);
        assertTrue(findings.stream().allMatch(f -> f.methodName().equals("helper")));
        assertEquals(2, findings.stream().map(Finding::callPath).distinct().count(),
                () -> "each finding should carry the path that reached it: " + findings);
    }
}
