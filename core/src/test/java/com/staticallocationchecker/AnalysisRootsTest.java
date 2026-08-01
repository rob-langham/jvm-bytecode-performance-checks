package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.DirectNew;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code analyze} does with the roots it is handed. A verification tool that reports "clean"
 * for input it never actually read is the most dangerous failure mode there is, so the emphasis
 * here is on inputs that produce no findings.
 */
class AnalysisRootsTest {

    private static final String DIRECT_NEW_RESOURCE =
            DirectNew.class.getName().replace('.', '/') + ".class";

    private Report analyze(Path... roots) {
        return new AllocationChecker().analyze(List.of(roots), List.of());
    }

    @Test
    void findsNothingInAnEmptyDirectory(@TempDir Path empty) {
        assertTrue(analyze(empty).isClean());
    }

    @Test
    void findsNothingInADirectoryOfNonClassFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("notes.txt"), "not bytecode");

        assertTrue(analyze(dir).isClean());
    }

    @Test
    void analysesClassesInNestedPackageDirectories(@TempDir Path root) throws IOException {
        Path target = root.resolve("deeply/nested/Foo.class");
        Files.createDirectories(target.getParent());
        copyDirectNewTo(target);

        assertEquals(1, analyze(root).findings().size(),
                "the walk must recurse, and must not depend on the file's own name");
    }

    @Test
    void combinesFindingsFromMultipleRoots(@TempDir Path a, @TempDir Path b) throws IOException {
        copyDirectNewTo(a.resolve("A.class"));
        copyDirectNewTo(b.resolve("B.class"));

        assertEquals(1, analyze(a, b).findings().size(),
                "the same class present under two roots is indexed once, by binary name");
    }

    @Test
    void failsLoudlyOnAMissingRoot(@TempDir Path parent) {
        Path missing = parent.resolve("does-not-exist");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> analyze(missing));

        assertTrue(thrown.getMessage().contains("Failed to walk"), thrown.getMessage());
    }

    @Test
    void analysesClassesInsideAJarRoot(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("fixtures.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(DIRECT_NEW_RESOURCE));
            out.write(directNewBytes());
            out.closeEntry();
        }

        Report report = analyze(jar);

        assertFalse(report.isClean(), "the jar contains an allocating annotated method");
        assertEquals(1, report.findings().size(), () -> "got " + report.findings());
    }

    @Test
    void ignoresFilesThatAreNotValidBytecodeOnlyByFailingLoudly(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("Corrupt.class"), new byte[] {1, 2, 3, 4});

        assertThrows(RuntimeException.class, () -> analyze(dir),
                "a corrupt class file must not be silently skipped");
    }

    private static void copyDirectNewTo(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(testClassesRoot().resolve(DIRECT_NEW_RESOURCE), target,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] directNewBytes() throws IOException {
        return Files.readAllBytes(testClassesRoot().resolve(DIRECT_NEW_RESOURCE));
    }
}
