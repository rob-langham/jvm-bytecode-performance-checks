package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The same fixture source, compiled at every release level the checker supports, analysed once per
 * level - and expected to produce exactly the same findings every time.
 *
 * <p>That identity is the whole point. A finding is keyed by category, not by the instruction that
 * produced it, and the categories are supposed to describe what the <em>source</em> allocates: a
 * concatenation is a STRING_CONCAT whether javac expanded it into a StringBuilder chain (below
 * release 9) or an invokedynamic (from 9). Without this matrix, a regression that only appeared on
 * release-8 or release-25 bytecode would pass the entire suite, because every checked-in fixture is
 * compiled at one level.
 *
 * <p>The fixtures are compiled by the build rather than here - one Gradle task per level, all
 * driven by a Java 25 javac, because javac 17 cannot emit release 21 or 25 - and their output
 * directories arrive as system properties. See the cross-release block in core/build.gradle.kts.
 *
 * <p>Release 8 carries a second claim: the fixture applies {@code @ZeroAllocations} directly, so a
 * green release-8 row is also the proof that a project building at release 8 can use this library's
 * annotations at all.
 */
class CrossReleaseMatrixTest {

    private static final String PACKAGE = "com.staticallocationchecker.crossrelease.";

    /**
     * Every finding the shared fixture produces, at every level. Not counts: a count passes when
     * two findings swap categories, which is exactly the failure this matrix exists to catch.
     */
    private static final List<String> EXPECTED = List.of(
            "ZERO_ALLOCATION_VIOLATION | HotPath#boundMethodReference | LAMBDA",
            "ZERO_ALLOCATION_VIOLATION | HotPath#box | BOXING",
            "ZERO_ALLOCATION_VIOLATION | HotPath#capturingLambda | LAMBDA",
            "ZERO_ALLOCATION_VIOLATION | HotPath#concat | STRING_CONCAT",
            "ZERO_ALLOCATION_VIOLATION | HotPath#concatInLoop | STRING_CONCAT",
            "ZERO_ALLOCATION_VIOLATION | HotPath#directNew | NEW",
            "ZERO_ALLOCATION_VIOLATION | HotPath#primitiveArray | NEW_ARRAY",
            "ZERO_ALLOCATION_VIOLATION | HotPath#referenceArray | NEW_ARRAY",
            "ZERO_ALLOCATION_VIOLATION | HotPath#varargsBoxedArguments | BOXING",
            "ZERO_ALLOCATION_VIOLATION | HotPath#varargsBoxedArguments | VARARGS_ARRAY",
            "ZERO_ALLOCATION_VIOLATION | HotPath#varargsConstantArguments | VARARGS_ARRAY");

    @ParameterizedTest(name = "release {0}")
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void reportsTheSameFindingsAtEveryReleaseLevel(int release) {
        assertEquals(EXPECTED, findingsIn(fixtureRoot("crossReleaseFixtures", release)),
                () -> "the findings for release " + release + " differ from the other levels. The "
                        + "source is identical at every level, so a category-keyed expectation must "
                        + "hold whatever javac emitted for it - that is what the categories mean");
    }

    /**
     * The clean methods stay clean at every level too. Asserted separately from the list above
     * because an absence is easy to lose in a long expectation, and because these are the shapes
     * most likely to be mistaken for allocations by a change to the classifier.
     */
    @ParameterizedTest(name = "release {0}")
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void leavesTheDeliberatelyCleanMethodsAloneAtEveryReleaseLevel(int release) {
        List<String> clean = findingsIn(fixtureRoot("crossReleaseFixtures", release)).stream()
                .filter(f -> f.contains("#nonCapturingLambda") || f.contains("#varargsExistingArray")
                        || f.contains("#reject") || f.contains("#clean"))
                .toList();

        assertEquals(List.of(), clean,
                () -> "at release " + release + ": a non-capturing lambda is a singleton, an "
                        + "existing array is not synthesised, Throwable allocation is exempt, and "
                        + "primitive arithmetic allocates nothing");
    }

    /**
     * Records need 17, so this half of the matrix starts there. It is a separate fixture, and a
     * separate output directory, precisely so that the expectation above can stay identical at all
     * five levels instead of growing a per-release exception.
     */
    @ParameterizedTest(name = "release {0}")
    @ValueSource(ints = {17, 21, 25})
    void findsARecordsGeneratedToStringFromSeventeenUp(int release) {
        assertEquals(
                List.of("ZERO_ALLOCATION_VIOLATION | RecordHotPath$Level#toString | RECORD_TO_STRING"),
                findingsIn(fixtureRoot("crossReleaseRecordFixtures", release)),
                () -> "at release " + release + ": toString builds a fresh String on every call, "
                        + "while the equals and hashCode sharing its bootstrap allocate nothing");
    }

    /** Runs the checker over one compiled level, discovering entry points from the annotations. */
    private static List<String> findingsIn(Path classes) {
        Report report = new AllocationChecker().analyze(List.of(classes), List.of());
        return report.findings().stream()
                .map(f -> f.kind()
                        + " | " + f.className().replace(PACKAGE, "") + "#" + f.methodName()
                        + " | " + f.category())
                .sorted()
                .toList();
    }

    /** Where the build put the fixtures it compiled at this level. */
    private static Path fixtureRoot(String property, int release) {
        String path = System.getProperty(property + "." + release);
        assertNotNull(path, property + "." + release + " is not set: the cross-release fixtures are "
                + "compiled by Gradle and handed over as system properties, so this test only runs "
                + "under the build (see core/build.gradle.kts)");
        Path root = Path.of(path);
        assertNotNull(root, path);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("No compiled fixtures at " + root + " for release " + release);
        }
        return root;
    }
}
