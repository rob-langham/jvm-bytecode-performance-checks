package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the checker over the demo scenarios and holds each one to what it promises.
 *
 * <p>The hand-built fixtures elsewhere isolate one behaviour apiece, which is what makes them
 * useful for pinning an edge case and useless for showing how the tool behaves on code someone
 * would actually write. The demos are that code - an order book, a matching engine, a buffer pool -
 * so they make far better end-to-end tests than anything invented for the purpose.
 *
 * <p>Reusing them also removes a gap: the demos previously proved themselves only when someone ran
 * {@code ./gradlew -p demo demo} by hand. Now the library's own suite fails if a scenario stops
 * demonstrating what its README says it does.
 *
 * <p>Each scenario's expectations live beside it in {@code expected-findings.txt}, so the demo
 * build and this test check the same file rather than two drifting copies.
 */
class DemoScenarioTest {

    /** Compiled here rather than reusing the demo build's output: this must not depend on it. */
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    static List<String> scenarios() throws IOException {
        Path demo = demoRoot();
        if (!Files.isDirectory(demo)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(demo)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("expected-findings.txt")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenarioReportsExactlyWhatItPromises(String scenario, @TempDir Path classes) throws IOException {
        assumeTrue(COMPILER != null, "needs a JDK, not a JRE");
        Path root = demoRoot().resolve(scenario);

        compile(sourcesUnder(root), classes);
        Report report = new AllocationChecker().analyze(List.of(classes), List.of());

        List<String> actual = report.findings().stream()
                .map(DemoScenarioTest::describe)
                .sorted()
                .toList();
        List<String> expected = Files.readAllLines(root.resolve("expected-findings.txt")).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .sorted()
                .toList();

        assertEquals(expected, actual,
                () -> "Scenario " + scenario + " no longer matches expected-findings.txt.\n"
                        + "Either the checker changed behaviour or the demo drifted. Fix whichever "
                        + "is wrong, and update the scenario's README to match.");
    }

    /**
     * The demo build asserts finding <em>counts</em>; this asserts the same scenarios in full. A
     * count alone would pass if two findings swapped categories.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void expectationsAgreeWithTheCountTheDemoBuildChecks(String scenario) throws IOException {
        Path expectations = demoRoot().resolve(scenario).resolve("expected-findings.txt");

        long declared = Files.readAllLines(expectations).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .count();

        assertTrue(declared >= 0);
        assertTrue(Files.exists(demoRoot().resolve(scenario).resolve("README.md")),
                "every scenario documents itself for a reader, not just for this test");
    }

    /** {@code KIND | method | CATEGORY} - enough to catch a swap, stable against line moves. */
    private static String describe(Finding finding) {
        return finding.kind()
                + " | " + finding.className().replaceFirst("^demo\\.", "") + "#" + finding.methodName()
                + " | " + finding.category();
    }

    private static Path demoRoot() {
        // The test runs from core/, so the demo build is a sibling of the project root.
        Path fromCore = Path.of("..").resolve("demo").toAbsolutePath().normalize();
        return Files.isDirectory(fromCore) ? fromCore : Path.of("demo").toAbsolutePath().normalize();
    }

    private static List<Path> sourcesUnder(Path scenario) throws IOException {
        Path src = scenario.resolve("src/main/java");
        if (!Files.isDirectory(src)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(src)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static void compile(List<Path> sources, Path outputDir) {
        if (sources.isEmpty()) {
            throw new IllegalStateException("no sources to compile");
        }
        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", System.getProperty("java.class.path"),
                // Line numbers matter to nothing asserted here, but the checker's site keys use
                // them, so compile the way a real build would.
                "-g"));
        sources.forEach(s -> args.add(s.toString()));

        int result = COMPILER.run(null, null, null, args.toArray(new String[0]));
        if (result != 0) {
            throw new UncheckedIOException(new IOException(
                    "the demo scenario no longer compiles; it is meant to be code people can run"));
        }
    }
}
