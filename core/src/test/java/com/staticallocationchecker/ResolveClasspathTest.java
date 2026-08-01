package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.findingsFor;
import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.Dispatch;
import com.staticallocationchecker.fixtures.Inheritance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The second parameter of {@code analyze}: classes that can be <em>resolved</em> through but are
 * never scanned for annotated entry points.
 *
 * <p>The distinction is the whole point. A dependency's code should be followed when a hot path
 * reaches into it, because that is where the allocation may be. Its own annotations are not this
 * build's business, and reporting findings for a method nobody here wrote would be noise.
 */
class ResolveClasspathTest {

    private Report analyze(Path analysisRoot, Path... resolveClasspath) {
        return new AllocationChecker().analyze(List.of(analysisRoot), List.of(resolveClasspath));
    }

    /**
     * Splits the compiled fixtures in two: the caller alone under analysis, the callee only on the
     * resolve classpath. Without the second, the call cannot be followed.
     */
    private Path[] splitInheritanceFixture(Path root) throws IOException {
        Path analysis = root.resolve("analysis");
        Path resolve = root.resolve("resolve");
        copy(Inheritance.class, analysis);
        copy(Inheritance.AllocatingChild.class, resolve);
        copy(Inheritance.AllocatingParent.class, resolve);
        copy(Inheritance.CleanChild.class, resolve);
        copy(Inheritance.CleanParent.class, resolve);
        return new Path[] {analysis, resolve};
    }

    @Test
    void followsACallIntoAClassOnlyOnTheResolveClasspath(@TempDir Path root) throws IOException {
        Path[] roots = splitInheritanceFixture(root);

        Report report = analyze(roots[0], roots[1]);

        assertTrue(report.findings().stream().anyMatch(f ->
                        f.kind() == Finding.Kind.ZERO_ALLOCATION_VIOLATION
                                && f.methodName().equals("inherited")),
                () -> "the allocation lives in a class only on the resolve classpath: "
                        + report.findings());
    }

    @Test
    void withoutTheResolveClasspathTheSameCallIsUnanalyzable(@TempDir Path root) throws IOException {
        Path[] roots = splitInheritanceFixture(root);

        Report report = analyze(roots[0]);

        assertTrue(report.findings().stream().allMatch(f -> f.kind() == Finding.Kind.UNANALYZABLE_CALL),
                () -> "with nothing to resolve against, the calls cannot be followed: "
                        + report.findings());
        assertFalse(report.isClean(), "and they must be reported, not assumed clean");
    }

    @Test
    void resolvingRemovesTheUnanalyzableFindings(@TempDir Path root) throws IOException {
        Path[] roots = splitInheritanceFixture(root);

        long withoutIt = analyze(roots[0]).findings().stream()
                .filter(f -> f.kind() == Finding.Kind.UNANALYZABLE_CALL).count();
        long withIt = analyze(roots[0], roots[1]).findings().stream()
                .filter(f -> f.kind() == Finding.Kind.UNANALYZABLE_CALL).count();

        assertTrue(withIt < withoutIt,
                "reducing UNANALYZABLE_CALL is the reason the parameter exists, "
                        + "was " + withoutIt + " now " + withIt);
    }

    @Test
    void doesNotScanTheResolveClasspathForEntryPoints(@TempDir Path root) throws IOException {
        Path analysis = root.resolve("analysis");
        Path resolve = root.resolve("resolve");
        // Nothing under analysis at all; the annotated, allocating class is only resolvable.
        Files.createDirectories(analysis);
        copy(com.staticallocationchecker.fixtures.DirectNew.class, resolve);

        Report report = analyze(analysis, resolve);

        assertTrue(report.isClean(),
                () -> "a dependency's own contracts are not this build's business: "
                        + report.findings());
    }

    @Test
    void resolvesVirtualDispatchThroughAnImplementationOnTheResolveClasspath(@TempDir Path root)
            throws IOException {
        Path analysis = root.resolve("analysis");
        Path resolve = root.resolve("resolve");
        copy(Dispatch.class, analysis);
        copy(Dispatch.Handler.class, resolve);
        copy(Dispatch.AllocatingHandler.class, resolve);
        copy(Dispatch.Base.class, resolve);
        copy(Dispatch.Impl.class, resolve);

        Report report = analyze(analysis, resolve);

        assertTrue(report.findings().stream().anyMatch(f -> f.methodName().equals("handle")),
                () -> "the interface implementation is resolvable, so it must be walked: "
                        + report.findings());
    }

    @Test
    void analysisRootsWinWhenAClassIsOnBoth(@TempDir Path root) throws IOException {
        Path analysis = root.resolve("analysis");
        Path resolve = root.resolve("resolve");
        copy(com.staticallocationchecker.fixtures.DirectNew.class, analysis);
        copy(com.staticallocationchecker.fixtures.DirectNew.class, resolve);

        Report report = analyze(analysis, resolve);

        assertEquals(1, findingsFor(report, com.staticallocationchecker.fixtures.DirectNew.class).size(),
                () -> "indexed once, and reported once: " + report.findings());
    }

    @Test
    void anEmptyResolveClasspathChangesNothing() {
        Report withEmpty = new AllocationChecker().analyze(List.of(testClassesRoot()), List.of());

        assertFalse(withEmpty.isClean(), "the fixture set has findings either way");
    }

    private static void copy(Class<?> type, Path root) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        Path target = root.resolve(resource);
        Files.createDirectories(target.getParent());
        Files.copy(testClassesRoot().resolve(resource), target, StandardCopyOption.REPLACE_EXISTING);
    }
}
