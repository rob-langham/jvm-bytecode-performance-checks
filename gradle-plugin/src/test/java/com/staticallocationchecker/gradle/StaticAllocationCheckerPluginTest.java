package com.staticallocationchecker.gradle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Gradle plugin's wiring and the task's pass/fail behaviour. */
class StaticAllocationCheckerPluginTest {

    private Project projectWith(Path projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply("io.github.rob-langham.static-allocation-checker");
        return project;
    }

    private StaticAllocationCheckerTask taskIn(Project project) {
        return (StaticAllocationCheckerTask) project.getTasks().getByName("checkStaticAllocation");
    }

    @Test
    void registersTheCheckTaskInTheVerificationGroup(@TempDir Path dir) {
        Task task = projectWith(dir).getTasks().findByName("checkStaticAllocation");

        assertNotNull(task, "the plugin must register its task");
        assertEquals("verification", task.getGroup());
        assertTrue(task instanceof StaticAllocationCheckerTask);
        assertNotNull(task.getDescription());
    }

    @Test
    void defaultsClassesDirsToTheMainSourceSetOutput(@TempDir Path dir) {
        Project project = projectWith(dir);
        project.getPlugins().apply("java");

        assertTrue(taskIn(project).getClassesDirs().getFiles().stream()
                        .anyMatch(f -> f.getPath().contains("classes")),
                "the default should follow the source set, not a hardcoded path");
    }

    @Test
    void attachesToTheCheckLifecycleTaskWhenJavaIsApplied(@TempDir Path dir) {
        Project project = projectWith(dir);
        project.getPlugins().apply("java");

        assertTrue(project.getTasks().getByName("check").getDependsOn().stream()
                        .anyMatch(d -> d.toString().contains("checkStaticAllocation")),
                "a verification task nobody runs verifies nothing");
    }

    @Test
    void taskPassesWhenThereAreNoFindings(@TempDir Path dir) throws IOException {
        Project project = projectWith(dir);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        StaticAllocationCheckerTask task = taskIn(project);
        task.getClassesDirs().setFrom(classes.toFile());

        assertDoesNotThrow(task::check);
    }

    @Test
    void taskFailsTheBuildWhenAnAllocationIsFound(@TempDir Path dir) throws IOException {
        StaticAllocationCheckerTask task = taskWithAllocatingClass(dir);

        GradleException thrown = assertThrows(GradleException.class, task::check);

        assertTrue(thrown.getMessage().contains("finding"), thrown.getMessage());
    }

    @Test
    void ignoreFailuresDowngradesFindingsToWarnings(@TempDir Path dir) throws IOException {
        StaticAllocationCheckerTask task = taskWithAllocatingClass(dir);
        task.getIgnoreFailures().set(true);

        assertDoesNotThrow(task::check, "adoption on an existing codebase needs a way in");
    }

    @Test
    void writesFindingsToTheReportFile(@TempDir Path dir) throws IOException {
        StaticAllocationCheckerTask task = taskWithAllocatingClass(dir);
        Path report = dir.resolve("out/findings.txt");
        task.getReportFile().set(report.toFile());
        task.getIgnoreFailures().set(true);

        task.check();

        assertTrue(Files.exists(report), "the report should exist");
        assertTrue(Files.readString(report).contains("ZERO_ALLOCATION_VIOLATION"),
                Files.readString(report));
    }

    @Test
    void taskFailsLoudlyWhenTheClassesDirectoryIsAbsent(@TempDir Path dir) {
        Project project = projectWith(dir);
        StaticAllocationCheckerTask task = taskIn(project);
        task.getClassesDirs().setFrom(dir.resolve("never-built").toFile());

        GradleException thrown = assertThrows(GradleException.class, task::check,
                "silently passing because there is nothing to check is the wrong default");

        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
    }

    @Test
    void taskFailsLoudlyWhenNothingIsConfiguredToAnalyse(@TempDir Path dir) {
        StaticAllocationCheckerTask task = taskIn(projectWith(dir));

        GradleException thrown = assertThrows(GradleException.class, task::check);

        assertTrue(thrown.getMessage().contains("no classes directories"), thrown.getMessage());
    }

    @Test
    void surfacesUnreadableBytecodeAsABuildFailureNotAnInternalError(@TempDir Path dir)
            throws IOException {
        // Far beyond any ASM's ceiling, so this stays a "too new" class file rather than becoming
        // a supported version the day someone bumps the ASM pin.
        StaticAllocationCheckerTask task = taskWithClassStampedAs(dir, 99);

        GradleException thrown = assertThrows(GradleException.class, task::check,
                "a parse failure must be a build failure, not a raw IllegalStateException"
                        + " with the guidance buried in a stack trace");

        assertTrue(thrown.getMessage().contains("could not analyse"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("major version 99"),
                "the checker's own explanation must reach the user: " + thrown.getMessage());
        assertNotNull(thrown.getCause(), "the original failure has to stay attached for --stacktrace");
    }

    @Test
    void surfacesCorruptBytecodeAsABuildFailure(@TempDir Path dir) throws IOException {
        Project project = projectWith(dir);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Files.write(classes.resolve("Corrupt.class"), new byte[] {1, 2, 3, 4});
        StaticAllocationCheckerTask task = taskIn(project);
        task.getClassesDirs().setFrom(classes.toFile());

        GradleException thrown = assertThrows(GradleException.class, task::check);

        assertTrue(thrown.getMessage().contains("could not analyse"), thrown.getMessage());
    }

    // ------------------------------------------------------------------------------------------
    // Multi-release jars.
    // ------------------------------------------------------------------------------------------

    @Test
    void targetReleaseDefaultsToTheProjectsCompileRelease(@TempDir Path dir) {
        Project project = projectWith(dir);
        project.getPlugins().apply("java");
        ((JavaCompile) project.getTasks().getByName("compileJava")).getOptions()
                .getRelease().set(11);

        assertEquals(Integer.valueOf(11), taskIn(project).getTargetRelease().get(),
                "the release the project compiles for is where 'which JVM is this built for'"
                        + " already lives, so it is the right default");
    }

    @Test
    void targetReleaseFallsBackToTheTargetCompatibilityWhenNoReleaseIsSet(@TempDir Path dir) {
        Project project = projectWith(dir);
        project.getPlugins().apply("java");
        project.getExtensions().getByType(JavaPluginExtension.class)
                .setTargetCompatibility(JavaVersion.VERSION_1_8);

        assertEquals(Integer.valueOf(8), taskIn(project).getTargetRelease().get(),
                "a build that never sets options.release still states its target somewhere");
    }

    @Test
    void targetReleaseIsUnsetWithoutTheJavaPlugin(@TempDir Path dir) {
        assertFalse(taskIn(projectWith(dir)).getTargetRelease().isPresent(),
                "with nothing to derive it from, base entries only - the old behaviour");
    }

    @Test
    void targetReleaseDecidesWhichCopyOfAMultiReleaseJarIsChecked(@TempDir Path dir)
            throws IOException {
        Project project = projectWith(dir);
        Path jar = multiReleaseJarWithVersionedAllocation(dir.resolve("dependency.jar"));
        StaticAllocationCheckerTask task = taskIn(project);
        task.getClassesDirs().setFrom(jar.toFile());

        assertDoesNotThrow(task::check,
                "unset means base entries only, and there is no allocating base entry here");

        task.getTargetRelease().set(17);

        GradleException thrown = assertThrows(GradleException.class, task::check,
                "at release 17 the versioned copy is the one that runs, and it allocates");
        assertTrue(thrown.getMessage().contains("finding"), thrown.getMessage());
    }

    /**
     * A multi-release jar whose only class lives under {@code META-INF/versions/17} - so it is
     * invisible below release 17 and an allocating hot path at or above it.
     */
    private static Path multiReleaseJarWithVersionedAllocation(Path target) throws IOException {
        String resource = "com/staticallocationchecker/fixtures/DirectNew.class";
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/versions/17/" + resource));
            out.write(Files.readAllBytes(
                    Path.of(System.getProperty("fixtureClasses")).resolve(resource)));
            out.closeEntry();
        }
        return target;
    }

    private StaticAllocationCheckerTask taskWithClassStampedAs(Path dir, int major)
            throws IOException {
        StaticAllocationCheckerTask task = taskWithAllocatingClass(dir);
        Path target = dir.resolve("classes/com/staticallocationchecker/fixtures/DirectNew.class");
        byte[] bytes = Files.readAllBytes(target);
        bytes[6] = (byte) (major >>> 8);
        bytes[7] = (byte) major;
        Files.write(target, bytes);
        return task;
    }

    private StaticAllocationCheckerTask taskWithAllocatingClass(Path dir) throws IOException {
        Project project = projectWith(dir);
        Path classes = dir.resolve("classes");
        Path target = classes.resolve("com/staticallocationchecker/fixtures/DirectNew.class");
        Files.createDirectories(target.getParent());
        Files.copy(Path.of(System.getProperty("fixtureClasses"))
                        .resolve("com/staticallocationchecker/fixtures/DirectNew.class"),
                target, StandardCopyOption.REPLACE_EXISTING);
        StaticAllocationCheckerTask task = taskIn(project);
        task.getClassesDirs().setFrom(classes.toFile());
        return task;
    }
}
