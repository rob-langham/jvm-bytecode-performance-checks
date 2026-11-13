package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.staticallocationchecker.fixtures.AnnotationSemantics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link AllocationChecker#contracts} answers "what is this method held to" the same way
 * {@link AllocationChecker#analyze} acts on it, for each placement rule the documentation gives.
 */
class ContractsTest {

    private static Map<String, Contract> contracts;

    @BeforeAll
    static void resolve() {
        contracts = new AllocationChecker().contracts(List.of(testClassesRoot()), List.of());
    }

    /** The contract of the one method named {@code name} in {@code type}. */
    private static Contract of(Class<?> type, String name) {
        List<Contract> found = contracts.entrySet().stream()
                .filter(e -> e.getKey().startsWith(type.getName() + "#" + name + "("))
                .map(Map.Entry::getValue)
                .toList();
        assertEquals(1, found.size(), () -> "expected exactly one " + type.getName() + "#" + name);
        return found.get(0);
    }

    @Test
    void bothAnnotationsOnOneMethodConflict() {
        assertEquals(Contract.CONFLICTING, of(AnnotationSemantics.BothOnOneMethod.class, "entry"));
    }

    @Test
    void aMethodLevelWarmupInsideAZeroAllocationTypeIsWarmup() {
        assertEquals(Contract.WARMUP, of(AnnotationSemantics.WarmupMethodInZeroAllocationType.class, "warm"));
        assertEquals(Contract.ZERO_ALLOCATIONS, of(AnnotationSemantics.WarmupMethodInZeroAllocationType.class, "hot"));
    }

    @Test
    void aTypeLevelContractReachesTheConstructorAndStaticInitialiser() {
        assertEquals(Contract.ZERO_ALLOCATIONS, of(AnnotationSemantics.TypeLevelReachesInitialisers.class, "<init>"));
        assertEquals(Contract.ZERO_ALLOCATIONS, of(AnnotationSemantics.TypeLevelReachesInitialisers.class, "<clinit>"));
    }

    @Test
    void anOverrideInheritsItsContractFromASuperclassOrInterface() {
        assertEquals(Contract.ZERO_ALLOCATIONS, of(AnnotationSemantics.UnannotatedOverride.class, "make"));
        assertEquals(Contract.ZERO_ALLOCATIONS, of(AnnotationSemantics.UnannotatedImplementation.class, "handle"));
    }

    @Test
    void anOverrideThatDeclaresItsOwnContractKeepsIt() {
        assertEquals(Contract.WARMUP, of(AnnotationSemantics.OverrideDeclaringItsOwnContract.class, "make"));
    }

    @Test
    void aMethodWithNoContractAnywhereHasNone() {
        assertEquals(Contract.NONE, of(AnnotationSemantics.MemberKinds.class, "callsPrivate"));
    }
}
