package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which copy of a class the checker reads out of a multi-release jar (JEP 238).
 *
 * <p>A multi-release jar holds the same class more than once, and the copies differ - that is the
 * entire reason the format exists. Indexing whichever copy happens to come first means analysing
 * code that may never run, and an allocation introduced only in the modern copy is then reported as
 * absent. So every case here is built as a matched pair, with the allocating variant on the base
 * side in one jar and on the versioned side in the other: a checker that always picked the same
 * side would pass half of these and fail the other half, and cannot pass both.
 *
 * <p>The two variants are compiled here rather than checked in because the point is that they are
 * genuinely two compilations of one class name, which is awkward to express as fixture sources in
 * one source set.
 */
class MultiReleaseJarTest {

    private static final String CLASS_RESOURCE = "mrjar/Widget.class";
    private static final String CALLER_RESOURCE = "mrjar/Caller.class";

    private static final String CLEAN_SOURCE = """
            package mrjar;

            import com.staticallocationchecker.annotations.ZeroAllocations;

            public class Widget {

                @ZeroAllocations
                public Object make(int n) {
                    return this;
                }
            }
            """;

    private static final String ALLOCATING_SOURCE = """
            package mrjar;

            import com.staticallocationchecker.annotations.ZeroAllocations;

            public class Widget {

                @ZeroAllocations
                public Object make(int n) {
                    return new Object();
                }
            }
            """;

    /** Reaches Widget#make from a hot path of its own, so a jar can be a resolve-only root. */
    private static final String CALLER_SOURCE = """
            package mrjar;

            import com.staticallocationchecker.annotations.ZeroAllocations;

            public class Caller {

                private final Widget widget = new Widget();

                @ZeroAllocations
                public Object run() {
                    return widget.make(1);
                }
            }
            """;

    @TempDir
    static Path compiled;

    private static Path cleanVariant;
    private static Path allocatingVariant;
    private static Path callerClass;

    @BeforeAll
    static void compileBothVariants() throws IOException {
        cleanVariant = compile("clean", CLEAN_SOURCE).resolve(CLASS_RESOURCE);
        Path allocatingClasses = compile("allocating", ALLOCATING_SOURCE);
        allocatingVariant = allocatingClasses.resolve(CLASS_RESOURCE);
        // Compiled against a Widget, so the call site names the right owner and descriptor. Which
        // Widget it was compiled against is irrelevant; the two variants share a signature.
        callerClass = compile("caller", CALLER_SOURCE, allocatingClasses).resolve(CALLER_RESOURCE);
    }

    // -----------------------------------------------------------------------------------------
    // The versioned entry wins, in both directions.
    // -----------------------------------------------------------------------------------------

    @Test
    void readsTheBaseEntryWhenTheTargetPredatesTheVersionedOne(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("clean-base.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, allocatingVariant));

        assertTrue(analyze(8, jar).isClean(),
                "a JVM at release 8 loads the base entry, so that is the code being verified");
    }

    @Test
    void readsTheVersionedEntryWhenTheTargetAdmitsIt(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("clean-base.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, allocatingVariant));

        assertEquals(1, analyze(17, jar).findings().size(),
                "at release 17 the versioned copy is the one that runs, and it allocates - missing"
                        + " that is exactly the silent pass this checker exists to prevent");
    }

    @Test
    void readsTheBaseEntryWhenTheTargetPredatesTheVersionedOneInverted(@TempDir Path dir)
            throws IOException {
        Path jar = multiReleaseJar(dir.resolve("allocating-base.jar"),
                entry(CLASS_RESOURCE, allocatingVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, cleanVariant));

        assertEquals(1, analyze(8, jar).findings().size(),
                "the same jar with the variants swapped must give the opposite answer, or the test"
                        + " above only proves that one fixed side was picked");
    }

    @Test
    void readsTheVersionedEntryWhenTheTargetAdmitsItInverted(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("allocating-base.jar"),
                entry(CLASS_RESOURCE, allocatingVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, cleanVariant));

        assertTrue(analyze(17, jar).isClean());
    }

    // -----------------------------------------------------------------------------------------
    // Choosing among several versioned entries.
    // -----------------------------------------------------------------------------------------

    @Test
    void picksTheHighestVersionedEntryTheTargetAdmits(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("layered.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/9/" + CLASS_RESOURCE, allocatingVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, cleanVariant));

        assertEquals(1, analyze(11, jar).findings().size(),
                "at release 11 the 9 entry is the highest one admitted, and it beats the base copy");
        assertTrue(analyze(17, jar).isClean(),
                "at release 17 the 17 entry beats the 9 entry, highest admitted version wins");
    }

    @Test
    void ignoresVersionedEntriesAboveTheTarget(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("future.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/25/" + CLASS_RESOURCE, allocatingVariant));

        assertTrue(analyze(17, jar).isClean(),
                "a release-25 entry is invisible to a release-17 JVM, so it is not what to analyse");
    }

    // -----------------------------------------------------------------------------------------
    // The manifest attribute is what makes a jar multi-release.
    // -----------------------------------------------------------------------------------------

    @Test
    void ignoresVersionedEntriesWithoutTheManifestFlag(@TempDir Path dir) throws IOException {
        Path jar = jar(dir.resolve("not-declared.jar"), false,
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/9/" + CLASS_RESOURCE, allocatingVariant));

        for (int release : new int[] {0, 8, 9, 11, 17, 25}) {
            assertTrue(analyze(release, jar).isClean(),
                    "without Multi-Release: true the jar is an ordinary jar with some files under"
                            + " META-INF, and no JVM at any release loads them: " + release);
        }
    }

    @Test
    void indexesNothingVersionedFromAnUndeclaredJarEvenWhenThereIsNoBaseEntry(@TempDir Path dir)
            throws IOException {
        Path jar = jar(dir.resolve("undeclared-versioned-only.jar"), false,
                entry("META-INF/versions/9/" + CLASS_RESOURCE, allocatingVariant));

        assertTrue(analyze(25, jar).isClean(),
                "the entry is unreachable at runtime, so there is nothing here to verify");
    }

    // -----------------------------------------------------------------------------------------
    // A class that exists only in a versioned entry.
    // -----------------------------------------------------------------------------------------

    @Test
    void discoversAnAnnotatedMethodInsideAVersionedOnlyEntry(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("versioned-only.jar"),
                entry("META-INF/versions/11/" + CLASS_RESOURCE, allocatingVariant));

        assertEquals(1, analyze(11, jar).findings().size(),
                "a versioned entry with no base copy is still a class a release-11 JVM loads, and"
                        + " its annotations are as real as any other's");
        assertTrue(analyze(8, jar).isClean(),
                "and at release 8 that class does not exist at all");
    }

    // -----------------------------------------------------------------------------------------
    // Both kinds of root.
    // -----------------------------------------------------------------------------------------

    @Test
    void resolvesVersionedEntriesOnTheResolveClasspathToo(@TempDir Path dir) throws IOException {
        Path callerRoot = dir.resolve("caller");
        Files.createDirectories(callerRoot.resolve("mrjar"));
        Files.copy(callerClass, callerRoot.resolve(CALLER_RESOURCE));
        Path dependency = multiReleaseJar(dir.resolve("dependency.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/17/" + CLASS_RESOURCE, allocatingVariant));

        Report atEight = new AllocationChecker(8)
                .analyze(List.of(callerRoot), List.of(dependency));
        Report atSeventeen = new AllocationChecker(17)
                .analyze(List.of(callerRoot), List.of(dependency));

        assertTrue(atEight.isClean(),
                "the base copy of the dependency allocates nothing, and it resolved - an"
                        + " unresolved call would have been reported instead");
        assertEquals(1, atSeventeen.findings().size(),
                "walking into a dependency has to walk into the copy that will actually run;"
                        + " a resolve-only root is where a multi-release jar normally turns up");
    }

    // -----------------------------------------------------------------------------------------
    // The default.
    // -----------------------------------------------------------------------------------------

    @Test
    void withoutATargetReleaseOnlyBaseEntriesAreRead(@TempDir Path dir) throws IOException {
        Path jar = multiReleaseJar(dir.resolve("clean-base.jar"),
                entry(CLASS_RESOURCE, cleanVariant),
                entry("META-INF/versions/9/" + CLASS_RESOURCE, allocatingVariant));
        Path versionedOnly = multiReleaseJar(dir.resolve("versioned-only.jar"),
                entry("META-INF/versions/9/" + CLASS_RESOURCE, allocatingVariant));

        assertTrue(new AllocationChecker().analyze(List.of(jar), List.of()).isClean(),
                "the no-argument checker reads base entries only - the behaviour of every release"
                        + " before targetRelease existed, pinned so it cannot drift by accident");
        assertTrue(new AllocationChecker().analyze(List.of(versionedOnly), List.of()).isClean());
        assertTrue(new AllocationChecker(0).analyze(List.of(jar), List.of()).isClean(),
                "and 0 is spelled out as the same thing");
    }

    @Test
    void anAllocatingBaseEntryIsStillFoundWithoutATargetRelease(@TempDir Path dir)
            throws IOException {
        Path jar = multiReleaseJar(dir.resolve("allocating-base.jar"),
                entry(CLASS_RESOURCE, allocatingVariant),
                entry("META-INF/versions/9/" + CLASS_RESOURCE, cleanVariant));

        assertEquals(1, new AllocationChecker().analyze(List.of(jar), List.of()).findings().size(),
                "base-only must mean the base entry, not nothing at all");
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    private static Report analyze(int targetRelease, Path root) {
        return new AllocationChecker(targetRelease).analyze(List.of(root), List.of());
    }

    private static Map.Entry<String, Path> entry(String name, Path classFile) {
        return Map.entry(name, classFile);
    }

    @SafeVarargs
    private static Path multiReleaseJar(Path target, Map.Entry<String, Path>... entries)
            throws IOException {
        return jar(target, true, entries);
    }

    @SafeVarargs
    private static Path jar(Path target, boolean multiRelease, Map.Entry<String, Path>... entries)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) {
            manifest.getMainAttributes().putValue("Multi-Release", "true");
        }
        Map<String, Path> contents = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : entries) {
            contents.put(e.getKey(), e.getValue());
        }
        Files.createDirectories(target.getParent());
        try (JarOutputStream out =
                new JarOutputStream(Files.newOutputStream(target), manifest)) {
            for (Map.Entry<String, Path> e : contents.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(Files.readAllBytes(e.getValue()));
                out.closeEntry();
            }
        }
        return target;
    }

    private static Path compile(String name, String source, Path... classpath) throws IOException {
        Path root = Files.createDirectories(compiled.resolve(name));
        Path sourceDir = Files.createDirectories(root.resolve("src/mrjar"));
        String simpleName = source.contains("class Caller") ? "Caller" : "Widget";
        Path file = sourceDir.resolve(simpleName + ".java");
        Files.writeString(file, source);
        Path classes = Files.createDirectories(root.resolve("classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests must run on a JDK, not a JRE");
        StringBuilder path = new StringBuilder(System.getProperty("java.class.path"));
        for (Path extra : classpath) {
            path.append(java.io.File.pathSeparatorChar).append(extra);
        }
        int result = compiler.run(null, null, null,
                "-classpath", path.toString(), "-d", classes.toString(), file.toString());

        assertEquals(0, result, "the " + name + " variant must compile");
        return classes;
    }
}
