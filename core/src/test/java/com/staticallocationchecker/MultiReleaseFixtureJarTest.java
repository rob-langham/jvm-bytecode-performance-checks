package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The same question as {@code MultiReleaseJarTest}, asked of a multi-release jar the build actually
 * produced rather than one assembled entry by entry inside a test.
 *
 * <p>The hand-built jars prove the selection rules. They do not prove that a jar a real toolchain
 * emits is laid out the way those rules assume - the manifest attribute where it is expected, the
 * versioned classes under a directory named for their release, the base copy at the root. One
 * fixture class compiled twice by two javac invocations and packaged by an ordinary Gradle
 * {@code Jar} task does prove that, because it is how a library shipping an MRJAR builds one.
 *
 * <p>See the multi-release block in core/build.gradle.kts; the jar arrives as a system property.
 */
class MultiReleaseFixtureJarTest {

    private static final String WIDGET = "com.staticallocationchecker.mrjar.Widget";

    @Test
    void analysesTheBaseCopyOfARealMultiReleaseJarAtReleaseEight() {
        Report report = new AllocationChecker(8).analyze(List.of(fixtureJar()), List.of());

        assertTrue(report.isClean(),
                "the base copy returns a cached instance, and a release-8 JVM loads the base copy");
    }

    @Test
    void analysesTheVersionedCopyOfARealMultiReleaseJarAtReleaseSeventeen() {
        Report report = new AllocationChecker(17).analyze(List.of(fixtureJar()), List.of());

        assertEquals(1, report.findings().size(), () -> "the release-17 copy allocates on its hot"
                + " path and is the copy a release-17 JVM runs: " + report.findings());
        Finding finding = report.findings().get(0);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, finding.kind());
        assertEquals(WIDGET, finding.className());
        assertEquals("describe", finding.methodName());
        assertEquals(AllocationCategory.NEW, finding.category());
    }

    @Test
    void withoutATargetReleaseARealMultiReleaseJarIsReadAsItsBaseCopy() {
        assertTrue(new AllocationChecker().analyze(List.of(fixtureJar()), List.of()).isClean(),
                "the default has to stay the pre-existing behaviour, on a real jar as much as on a"
                        + " synthetic one");
    }

    /** The multi-release jar the build assembled. */
    static Path fixtureJar() {
        String path = System.getProperty("mrjarFixtureJar");
        assertNotNull(path, "mrjarFixtureJar is not set: the multi-release fixture is assembled by"
                + " Gradle and handed over as a system property, so this test only runs under the"
                + " build (see core/build.gradle.kts)");
        Path jar = Path.of(path);
        assertTrue(Files.isRegularFile(jar), "No multi-release fixture jar at " + jar);
        return jar;
    }

    /** Where the build put one of the two compilations that went into the fixture jar. */
    static Path fixtureClasses(int release) {
        String path = System.getProperty("mrjarFixtureClasses." + release);
        assertNotNull(path, "mrjarFixtureClasses." + release + " is not set");
        Path root = Path.of(path);
        assertTrue(Files.isDirectory(root), "No compiled fixture at " + root);
        return root;
    }
}
