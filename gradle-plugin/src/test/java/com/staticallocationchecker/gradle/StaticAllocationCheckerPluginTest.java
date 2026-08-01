package com.staticallocationchecker.gradle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
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
