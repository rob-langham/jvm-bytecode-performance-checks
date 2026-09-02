package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.RecordAllocations;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A record's generated members. All three compile to invokedynamic against the same bootstrap, so
 * classifying by bootstrap alone would either miss the allocating one or slander the other two.
 */
class RecordMembersTest {

    private Report analyze() {
        return new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
    }

    @Test
    void findsTheStringARecordsToStringBuilds() {
        List<Finding> findings = findingsFor(analyze(), RecordAllocations.Point.class);

        assertEquals(1, findings.size(), () -> "expected toString's allocation only, got " + findings);
        assertEquals("toString", findings.get(0).methodName());
        assertEquals(AllocationCategory.RECORD_TO_STRING, findings.get(0).category());
    }

    @Test
    void leavesTheGeneratedEqualsAndHashCodeClean() {
        List<Finding> findings = findingsFor(analyze(), RecordAllocations.Point.class);

        assertTrue(findings.stream().noneMatch(
                        f -> f.methodName().equals("equals") || f.methodName().equals("hashCode")),
                () -> "equals and hashCode read the components and return a primitive: " + findings);
    }

    @Test
    void attributesTheAllocationToTheRecordNotItsCaller() {
        List<Finding> findings = findingsFor(analyze(), RecordAllocations.class);

        assertEquals(List.of(), findings,
                () -> "the caller only invokes the generated members: " + findings);
    }
}
