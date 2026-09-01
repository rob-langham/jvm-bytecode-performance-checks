package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * String concatenation compiled below release 9, where there is no {@code StringConcatFactory}
 * invokedynamic: javac expands {@code a + b} into a {@link StringBuilder} chain instead.
 *
 * <p>The bytecode is compiled here rather than checked in, because the point of the test is what a
 * real javac emits at that release level - a hand-assembled approximation of the shape would only
 * ever prove that the classifier agrees with this test's idea of javac.
 */
class Release8StringConcatTest {

    private static final String SOURCE = """
            package release8;

            public class Concat {

                private final StringBuilder reused = new StringBuilder();

                public String concat(String a, int b) {
                    return a + b;
                }

                public String concatOfManyParts(String a, String b, long c) {
                    return "[" + a + "/" + b + "/" + c + "]";
                }

                public StringBuilder escapingBuilder() {
                    StringBuilder builder = new StringBuilder();
                    builder.append("x");
                    return builder;
                }

                public String reuse(int n) {
                    reused.setLength(0);
                    reused.append(n);
                    return reused.toString();
                }
            }
            """;

    @TempDir
    static Path classes;

    @BeforeAll
    static void compileAtReleaseEight() throws IOException {
        Path source = Files.createDirectories(classes.resolve("src")).resolve("Concat.java");
        Files.writeString(source, SOURCE);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests must run on a JDK, not a JRE");
        int result = compiler.run(null, null, null,
                "--release", "8", "-d", classes.toString(), source.toString());

        assertEquals(0, result, "the release-8 fixture must compile");
    }

    private static List<Finding> findingsFor(String method) {
        Report report = new AllocationChecker()
                .analyze(List.of(classes), List.of(), List.of("release8.Concat#" + method));
        return report.findings();
    }

    private static List<AllocationCategory> categoriesOf(String method) {
        return findingsFor(method).stream()
                .filter(f -> f.category() != null)
                .map(Finding::category)
                .collect(Collectors.toList());
    }

    @Test
    void classifiesAStringBuilderConcatChainAsStringConcat() {
        assertEquals(List.of(AllocationCategory.STRING_CONCAT), categoriesOf("concat"),
                "a + b at release 8 is the same allocation as a + b at release 9, and must "
                        + "carry the same category whatever the compiler expanded it into");
    }

    @Test
    void reportsOneFindingPerConcatExpressionRatherThanOnePerAppend() {
        assertEquals(List.of(AllocationCategory.STRING_CONCAT), categoriesOf("concatOfManyParts"),
                "one source-level concatenation is one allocation, however many appends it took");
    }

    @Test
    void doesNotReportTheChainsOwnCallsAsUnanalyzable() {
        List<Finding> findings = findingsFor("concat");

        assertEquals(1, findings.size(), () -> "the append/toString calls belong to the reported "
                + "concatenation, not to a trail of their own: " + findings);
        assertEquals(Finding.Kind.ZERO_ALLOCATION_VIOLATION, findings.get(0).kind());
    }

    @Test
    void stillReportsAHandWrittenBuilderAsANewAllocation() {
        assertTrue(categoriesOf("escapingBuilder").contains(AllocationCategory.NEW),
                () -> "a builder that escapes instead of ending in toString is a hand-written "
                        + "object, not a concatenation: " + findingsFor("escapingBuilder"));
    }

    @Test
    void doesNotExemptTheAppendsOfABuilderItDidNotAllocate() {
        List<Finding> findings = findingsFor("reuse");

        assertTrue(findings.stream().anyMatch(f -> f.kind() == Finding.Kind.UNANALYZABLE_CALL),
                () -> "appends on a builder held in a field are ordinary calls into the JDK and "
                        + "must stay visible as unanalyzable: " + findings);
    }

    @Test
    void reportsTheFieldBuildersAllocationInTheConstructor() {
        assertEquals(List.of(AllocationCategory.NEW), categoriesOf("<init>"),
                "a builder kept in a field is allocated once and reused: a plain NEW");
    }
}
