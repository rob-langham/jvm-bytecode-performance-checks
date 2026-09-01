package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.Dispatch;
import com.staticallocationchecker.fixtures.Inheritance;
import com.staticallocationchecker.fixtures.Varargs;
import com.staticallocationchecker.fixtures.VarargsArguments;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * How call sites are resolved to the bytecode that actually runs: through virtual and interface
 * dispatch, through inheritance, and through the array a varargs call site synthesises.
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

        assertTrue(findings.stream().noneMatch(f -> f.methodName().equals("passesExistingArray")),
                () -> "passing an existing array allocates nothing: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.methodName().equals("passesPrimitiveVarargs")),
                () -> "the synthesised array is an allocation: " + findings);
    }

    @Test
    void categorisesVarargsArraysDistinctlyFromExplicitArrays() {
        Map<String, AllocationCategory> byMethod = findingsFor(analyze(), Varargs.class).stream()
                .collect(Collectors.toMap(Finding::methodName, Finding::category));

        assertEquals(AllocationCategory.VARARGS_ARRAY, byMethod.get("passesPrimitiveVarargs"));
        assertEquals(AllocationCategory.VARARGS_ARRAY, byMethod.get("passesObjectVarargs"));
        assertEquals(AllocationCategory.NEW_ARRAY, byMethod.get("passesExplicitArrayToAnOrdinaryParameter"),
                "identical bytecode to a varargs call site; only the callee's flag tells them apart");
    }

    @Test
    void recognisesAVarargsCallSiteWhoseArgumentsAllocateFirst() {
        Map<String, List<AllocationCategory>> byMethod = categoriesByMethod(VarargsArguments.class);

        assertEquals(List.of(AllocationCategory.VARARGS_ARRAY, AllocationCategory.BOXING,
                        AllocationCategory.BOXING),
                byMethod.get("boxedArguments"),
                "the boxing conversions sit between the array and the call that consumes it");
        assertEquals(List.of(AllocationCategory.VARARGS_ARRAY, AllocationCategory.NEW),
                byMethod.get("constructedArgument"),
                "an argument constructed into the array does not hide the call site");
        assertEquals(List.of(AllocationCategory.VARARGS_ARRAY, AllocationCategory.BOXING),
                byMethod.get("formattedArguments"),
                "String.format is the varargs call every hot path meets first");
    }

    @Test
    void recognisesAVarargsCallSiteNestedInsideAnother() {
        assertEquals(
                List.of(AllocationCategory.VARARGS_ARRAY, AllocationCategory.VARARGS_ARRAY,
                        AllocationCategory.BOXING),
                categoriesByMethod(VarargsArguments.class).get("varargsWithinVarargs"),
                "both call sites synthesise an array, and neither masks the other");
    }

    /** Allocation categories, in bytecode order, per method of a fixture. */
    private Map<String, List<AllocationCategory>> categoriesByMethod(Class<?> fixture) {
        return findingsFor(analyze(), fixture).stream()
                .filter(f -> f.category() != null)
                .collect(Collectors.groupingBy(
                        Finding::methodName,
                        Collectors.mapping(Finding::category, Collectors.toList())));
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
