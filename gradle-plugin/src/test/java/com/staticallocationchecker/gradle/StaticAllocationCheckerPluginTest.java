package com.staticallocationchecker.gradle;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Gradle plugin's wiring and the task's pass/fail behaviour. */
class StaticAllocationCheckerPluginTest {

    private Project projectWith(Path projectDir) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPlugins().apply("com.staticallocationchecker.static-allocation-checker");
        return project;
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
    void taskPassesWhenThereAreNoCompiledClasses(@TempDir Path dir) throws IOException {
        Project project = projectWith(dir);
        Files.createDirectories(dir.resolve("build/classes/java/main"));

        StaticAllocationCheckerTask task =
                (StaticAllocationCheckerTask) project.getTasks().getByName("checkStaticAllocation");

        task.check();
    }

    @Test
    void taskFailsTheBuildWhenAnAllocationIsFound(@TempDir Path dir) throws IOException {
        Project project = projectWith(dir);
        Path classes = dir.resolve("build/classes/java/main/com/staticallocationchecker/fixtures");
        Files.createDirectories(classes);
        copyFixtureTo(classes.resolve("DirectNew.class"));

        StaticAllocationCheckerTask task =
                (StaticAllocationCheckerTask) project.getTasks().getByName("checkStaticAllocation");

        GradleException thrown = assertThrows(GradleException.class, task::check);
        assertTrue(thrown.getMessage().contains("finding"), thrown.getMessage());
    }

    @Test
    @Disabled("GAP: the task hardcodes build/classes/java/main and analyses nothing else, so a "
            + "missing directory, a non-default source set, a multi-release or Kotlin/Scala output "
            + "directory all silently pass. It also exposes no inputs/outputs, so it is neither "
            + "configurable nor incremental")
    void taskFailsLoudlyWhenTheClassesDirectoryIsAbsent(@TempDir Path dir) {
        Project project = projectWith(dir);

        StaticAllocationCheckerTask task =
                (StaticAllocationCheckerTask) project.getTasks().getByName("checkStaticAllocation");

        assertThrows(GradleException.class, task::check,
                "silently passing because there is nothing to check is the wrong default");
    }

    private static void copyFixtureTo(Path target) throws IOException {
        Path source = Path.of(System.getProperty("fixtureClasses"))
                .resolve("com/staticallocationchecker/fixtures/DirectNew.class");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
