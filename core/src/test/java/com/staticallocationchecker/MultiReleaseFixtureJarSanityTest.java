package com.staticallocationchecker;

import static com.staticallocationchecker.MultiReleaseFixtureJarTest.fixtureClasses;
import static com.staticallocationchecker.MultiReleaseFixtureJarTest.fixtureJar;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The anchor: does the checker's hand-rolled multi-release resolution agree with the JVM's?
 *
 * <p>{@link MultiReleaseJar} re-implements JEP 238 entry selection by hand, because the JDK's own
 * versioned {@code JarFile} constructor and {@code Runtime.Version} are Java 9 API and the library
 * is compiled at {@code --release 8} so it can run on the oldest JVM whose bytecode it analyses. A
 * re-implementation is only as good as the thing it is checked against, and checking it against
 * this test's own idea of the rules would prove nothing.
 *
 * <p>So this test - which is test code, running on the build's 17 toolchain, where Java 9+ API is
 * perfectly allowed - opens the very same fixture jar with the platform's versioned machinery and
 * asks it which bytes a JVM at each release would load. Then it asserts the checker analysed those
 * same bytes. Under the CI matrix this runs on every supported JDK, so the day a platform behaviour
 * changes underneath the hand-rolled copy, a row goes red instead of the checker quietly reporting
 * on code nobody runs.
 */
class MultiReleaseFixtureJarSanityTest {

    private static final String WIDGET_RESOURCE = "com/staticallocationchecker/mrjar/Widget.class";

    @ParameterizedTest(name = "release {0}")
    @ValueSource(ints = {8, 17})
    void theJvmLoadsTheVariantTheCheckerAnalysed(int release) throws IOException {
        byte[] platformBytes = bytesTheJvmWouldLoad(release);
        // The two compilations that went into the jar, kept as loose class files: whichever the
        // platform picked must be byte-identical to one of them, and to the expected one.
        Path expectedVariant = fixtureClasses(release == 8 ? 8 : 17);

        assertArrayEquals(Files.readAllBytes(expectedVariant.resolve(WIDGET_RESOURCE)),
                platformBytes,
                () -> "at release " + release + " the JVM resolves a different copy of Widget than"
                        + " the fixture layout says it should - the fixture, not the checker, is"
                        + " wrong");
        assertEquals(findingsIn(new AllocationChecker(release), expectedVariant),
                findingsIn(new AllocationChecker(release), fixtureJar()),
                () -> "at release " + release + " the checker's reading of the multi-release jar"
                        + " must be the same as its reading of the copy the JVM actually loads");
    }

    /** What the platform's own versioned jar handling resolves {@code Widget.class} to. */
    private static byte[] bytesTheJvmWouldLoad(int release) throws IOException {
        try (JarFile jar = new JarFile(fixtureJar().toFile(), true, JarFile.OPEN_READ,
                Runtime.Version.parse(String.valueOf(release)))) {
            JarEntry entry = jar.getJarEntry(WIDGET_RESOURCE);
            assertNotNull(entry, "the fixture jar must contain " + WIDGET_RESOURCE);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static List<String> findingsIn(AllocationChecker checker, Path root) {
        return checker.analyze(List.of(root), List.of()).findings().stream()
                .map(f -> f.kind() + " | " + f.className() + "#" + f.methodName() + " | "
                        + f.category())
                .sorted()
                .toList();
    }
}
