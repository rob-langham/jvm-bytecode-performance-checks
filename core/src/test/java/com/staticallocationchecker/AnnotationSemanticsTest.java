package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.AnnotatedConstructors;
import com.staticallocationchecker.fixtures.AnnotationSemantics;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Which declarations the annotations reach, and what they mean where they land. */
class AnnotationSemanticsTest {

    private List<Finding> findings(Class<?> fixture) {
        Report report = new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());
        return findingsFor(report, fixture);
    }

    @Test
    void annotationsAreRetainedAtRuntimeSoBytecodeCanSeeThem() {
        assertEquals(RetentionPolicy.RUNTIME,
                com.staticallocationchecker.annotations.ZeroAllocations.class
                        .getAnnotation(Retention.class).value());
        assertEquals(RetentionPolicy.RUNTIME,
                com.staticallocationchecker.annotations.AllocationsForWarmup.class
                        .getAnnotation(Retention.class).value());
    }

    @Test
    void annotationsCanBeAppliedToConstructors() {
        assertTrue(targetsOf(com.staticallocationchecker.annotations.ZeroAllocations.class)
                        .contains(ElementType.CONSTRUCTOR),
                "@ZeroAllocations must be placeable on a constructor");
        assertTrue(targetsOf(com.staticallocationchecker.annotations.AllocationsForWarmup.class)
                        .contains(ElementType.CONSTRUCTOR),
                "@AllocationsForWarmup must be placeable on a constructor");
    }

    private static Set<ElementType> targetsOf(Class<? extends java.lang.annotation.Annotation> annotation) {
        return Set.of(annotation.getAnnotation(Target.class).value());
    }

    @Test
    void checksAConstructorAnnotatedDirectlyRatherThanViaItsType() {
        List<Finding> findings = findings(AnnotatedConstructors.ZeroAllocationConstructor.class);

        assertEquals(1, findings.size(),
                () -> "only the allocating annotated constructor should be flagged, got " + findings);
        assertEquals("<init>", findings.get(0).methodName());
        assertEquals(AllocationCategory.NEW_ARRAY, findings.get(0).category());
        assertEquals("(I)V", findings.get(0).methodDescriptor(),
                "the int overload allocates; the Object overload and the unannotated one do not");
    }

    @Test
    void appliesTheWarmupContractToAnAnnotatedConstructor() {
        assertEquals(List.of(), findings(AnnotatedConstructors.WarmupConstructor.class),
                "guarded and cached in a constructor is still compliant warmup");

        List<Finding> violations = findings(AnnotatedConstructors.NonCompliantWarmupConstructor.class);
        assertEquals(1, violations.size(), () -> "got " + violations);
        assertEquals(Finding.Kind.WARMUP_NOT_GUARDED, violations.get(0).kind());
    }

    @Test
    void typeLevelAnnotationReachesConstructorsAndStaticInitialisers() {
        Set<String> flagged = findings(AnnotationSemantics.TypeLevelReachesInitialisers.class).stream()
                .map(Finding::methodName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("<init>", "<clinit>"), flagged,
                "the field initialiser and the static initialiser both allocate");
    }

    @Test
    void annotationAppliesToStaticAndPrivateMethodsAlike() {
        Set<String> flagged = findings(AnnotationSemantics.MemberKinds.class).stream()
                .map(Finding::methodName)
                .collect(Collectors.toSet());

        assertTrue(flagged.contains("staticMethod"), () -> "static method not checked: " + flagged);
        assertTrue(flagged.contains("privateMethod"), () -> "private method not checked: " + flagged);
    }

    @Test
    void contractAppliesToAnOverrideOfAnAnnotatedMethod() {
        List<Finding> findings = findings(AnnotationSemantics.UnannotatedOverride.class);

        assertEquals(1, findings.size(),
                () -> "the override of an @ZeroAllocations method allocates, got " + findings);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, findings.get(0).kind());
    }

    @Test
    void contractAppliesToAnImplementationOfAnAnnotatedInterfaceMethod() {
        List<Finding> findings = findings(AnnotationSemantics.UnannotatedImplementation.class);

        assertEquals(1, findings.size(),
                () -> "an interface can declare the contract implementations must honour, got " + findings);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, findings.get(0).kind());
    }

    @Test
    void anOverridesOwnDeclarationBeatsTheInheritedContract() {
        assertEquals(List.of(), findings(AnnotationSemantics.OverrideDeclaringItsOwnContract.class),
                "the override declares @AllocationsForWarmup and is compliant warmup; the "
                        + "inherited @ZeroAllocations must not override an explicit choice");
    }
}
