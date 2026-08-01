package com.staticallocationchecker.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the plugin through a real Gradle build, on the demo scenarios.
 *
 * <p>Everything else exercises the task by calling {@code check()} directly, which never proves the
 * plugin is wired into a build correctly: not that the task is registered, nor that the defaults
 * resolve, nor that a finding actually fails the build. This runs Gradle for real and asserts on
 * what a user would see.
 *
 * <p>The project it builds is assembled from a demo scenario's sources, so the fixtures stay the
 * same realistic code the demos and {@code DemoScenarioTest} use rather than something invented
 * here and free to disagree with both.
 */
class DemoScenarioFunctionalTest {

    private static Path demoRoot() {
        Path fromModule = Path.of("..").resolve("demo").toAbsolutePath().normalize();
        return Files.isDirectory(fromModule) ? fromModule : Path.of("demo").toAbsolutePath().normalize();
    }

    /**
     * Builds a standalone project from a scenario's sources. Standalone rather than running the
     * demo build itself, so this tests the plugin rather than the demo build's own wiring.
     */
    private void writeProject(Path projectDir, String scenario, String extraConfig) throws IOException {
        Path source = demoRoot().resolve(scenario).resolve("src/main/java");
        Path target = projectDir.resolve("src/main/java");
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }

        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"scenario\"\n");
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.github.rob-langham.static-allocation-checker")
                }

                repositories { mavenCentral() }

                dependencies {
                    implementation(files(%s))
                }

                %s
                """.formatted(annotationsClasspath(), extraConfig));
    }

    /** The annotations come from this build's own runtime classpath, not a published artifact. */
    private static String annotationsClasspath() {
        return Stream.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .filter(entry -> entry.contains("core") || entry.contains("asm"))
                .map(entry -> "\"" + entry.replace("\\", "\\\\") + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
    }

    private GradleRunner runner(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("checkStaticAllocation", "--stacktrace");
    }

    @Test
    void aCleanScenarioPassesTheBuild(@TempDir Path projectDir) throws IOException {
        writeProject(projectDir, "02-clean-hot-path", "");

        BuildResult result = runner(projectDir).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkStaticAllocation").getOutcome());
        assertTrue(result.getOutput().contains("no findings"), result.getOutput());
    }

    @Test
    void anAllocatingScenarioFailsTheBuildAndNamesWhatItFound(@TempDir Path projectDir)
            throws IOException {
        writeProject(projectDir, "01-zero-allocation-basics", "");

        BuildResult result = runner(projectDir).buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":checkStaticAllocation").getOutcome());
        assertTrue(result.getOutput().contains("static allocation finding"), result.getOutput());
        // The categories a reader of the README would expect to see named.
        for (String category : List.of("NEW", "BOXING", "STRING_CONCAT", "LAMBDA")) {
            assertTrue(result.getOutput().contains(category),
                    () -> "expected " + category + " in the build output:\n" + result.getOutput());
        }
    }

    @Test
    void ignoreFailuresReportsWithoutBreakingTheBuild(@TempDir Path projectDir) throws IOException {
        writeProject(projectDir, "01-zero-allocation-basics", """
                tasks.checkStaticAllocation {
                    ignoreFailures.set(true)
                }
                """);

        BuildResult result = runner(projectDir).build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkStaticAllocation").getOutcome());
        assertTrue(result.getOutput().contains("ignoreFailures is set"), result.getOutput());
    }

    @Test
    void theTaskRunsAsPartOfCheck(@TempDir Path projectDir) throws IOException {
        writeProject(projectDir, "02-clean-hot-path", "");

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("check")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkStaticAllocation").getOutcome(),
                "a verification task nobody runs verifies nothing");
    }

    @Test
    void findingsAreWrittenToTheReportFile(@TempDir Path projectDir) throws IOException {
        writeProject(projectDir, "03-warmup-contract", """
                tasks.checkStaticAllocation {
                    ignoreFailures.set(true)
                }
                """);

        runner(projectDir).build();

        Path report = projectDir.resolve("build/reports/static-allocation-checker/findings.txt");
        assertTrue(Files.exists(report), "the default report location should be written");
        String text = Files.readString(report);
        assertTrue(text.contains("WARMUP_NOT_GUARDED"), text);
        assertTrue(text.contains("WARMUP_NOT_CACHED"), text);
    }
}
