package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Value semantics of the types the plugins serialise and compare. */
class FindingTest {

    private static Finding finding(String methodName, AllocationCategory category) {
        return new Finding(
                Finding.Kind.ZERO_ALLOCATION_VIOLATION,
                "com.example.Foo", methodName, "()V", 7, category, List.of("com.example.Foo#" + methodName));
    }

    @Test
    void equalFindingsCompareAndHashAlike() {
        assertEquals(finding("bar", AllocationCategory.NEW), finding("bar", AllocationCategory.NEW));
        assertEquals(finding("bar", AllocationCategory.NEW).hashCode(),
                finding("bar", AllocationCategory.NEW).hashCode());
    }

    @Test
    void findingsDifferingInAnyComponentAreUnequal() {
        Finding base = finding("bar", AllocationCategory.NEW);

        assertNotEquals(base, finding("other", AllocationCategory.NEW));
        assertNotEquals(base, finding("bar", AllocationCategory.BOXING));
        assertNotEquals(base, new Finding(
                Finding.Kind.WARMUP_NOT_CACHED, "com.example.Foo", "bar", "()V", 7,
                AllocationCategory.NEW, List.of("com.example.Foo#bar")));
        assertNotEquals(base, new Finding(
                Finding.Kind.ZERO_ALLOCATION_VIOLATION, "com.example.Foo", "bar", "()V", 8,
                AllocationCategory.NEW, List.of("com.example.Foo#bar")));
        assertNotEquals(base, new Finding(
                Finding.Kind.ZERO_ALLOCATION_VIOLATION, "com.example.Foo", "bar", "()V", 7,
                AllocationCategory.NEW, List.of("com.example.Foo#bar", "extra")));
    }

    @Test
    void findingIsNotEqualToOtherTypesOrNull() {
        assertNotEquals(finding("bar", AllocationCategory.NEW), "not a finding");
        assertNotEquals(null, finding("bar", AllocationCategory.NEW));
    }

    @Test
    void callPathIsDefensivelyCopiedAndUnmodifiable() {
        List<String> mutable = new ArrayList<>(List.of("a"));
        Finding f = new Finding(
                Finding.Kind.ZERO_ALLOCATION_VIOLATION, "C", "m", "()V", 1, AllocationCategory.NEW, mutable);

        mutable.add("b");

        assertEquals(List.of("a"), f.callPath(), "later mutation of the source list must not leak in");
        assertThrows(UnsupportedOperationException.class, () -> f.callPath().add("c"));
    }

    @Test
    void unanalyzableCallCarriesNoCategory() {
        Finding f = new Finding(
                Finding.Kind.UNANALYZABLE_CALL, "C", "m", "()V", 1, null, List.of("C#m"));

        assertNull(f.category());
    }

    @Test
    void toStringNamesEveryComponent() {
        String text = finding("bar", AllocationCategory.NEW).toString();

        assertTrue(text.contains("ZERO_ALLOCATION_VIOLATION"), text);
        assertTrue(text.contains("com.example.Foo"), text);
        assertTrue(text.contains("bar"), text);
        assertTrue(text.contains("NEW"), text);
    }

    @Test
    void reportIsCleanOnlyWhenEmpty() {
        Report withFinding = new Report(List.of(finding("bar", AllocationCategory.NEW)));

        assertTrue(new Report(List.of()).isClean());
        assertFalse(withFinding.isClean());
        assertEquals(1, withFinding.findings().size());
    }

    @Test
    void reportFindingsAreDefensivelyCopiedAndUnmodifiable() {
        List<Finding> mutable = new ArrayList<>();
        mutable.add(finding("bar", AllocationCategory.NEW));
        Report report = new Report(mutable);

        mutable.clear();

        assertEquals(1, report.findings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> report.findings().add(finding("baz", AllocationCategory.NEW)));
    }

    @Test
    void reportsWithEqualFindingsAreEqual() {
        Report a = new Report(List.of(finding("bar", AllocationCategory.NEW)));
        Report b = new Report(List.of(finding("bar", AllocationCategory.NEW)));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Report(List.of()));
    }
}
