package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Disabled("GAP: @Target is {METHOD, TYPE}, so neither annotation can be placed on a constructor "
            + "at all - a constructor on a hot path can only be covered by annotating the whole type")
    void annotationsCanBeAppliedToConstructors() {
        Set<ElementType> targets = Set.of(com.staticallocationchecker.annotations.ZeroAllocations.class
                .getAnnotation(Target.class).value());

        assertTrue(targets.contains(ElementType.CONSTRUCTOR), () -> "targets were " + targets);
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
    @Disabled("GAP: Java annotations are not inherited by overrides, and the checker does not "
            + "consult supertype declarations, so overriding an annotated method silently drops "
            + "the contract - the most likely way for a real codebase to lose coverage")
    void contractAppliesToAnOverrideOfAnAnnotatedMethod() {
        List<Finding> findings = findings(AnnotationSemantics.UnannotatedOverride.class);

        assertEquals(1, findings.size(),
                () -> "the override of an @ZeroAllocations method allocates, got " + findings);
    }
}
