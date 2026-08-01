package com.staticallocationchecker.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Maven goal's binding and its pass/fail behaviour. */
class StaticAllocationCheckerMojoTest {

    private StaticAllocationCheckerMojo mojoFor(Path outputDirectory) throws Exception {
        StaticAllocationCheckerMojo mojo = new StaticAllocationCheckerMojo();
        Field field = StaticAllocationCheckerMojo.class.getDeclaredField("outputDirectory");
        field.setAccessible(true);
        field.set(mojo, outputDirectory.toFile());
        return mojo;
    }

    /**
     * The descriptor, not the annotation, is what Maven reads at build time - {@code @Mojo} has
     * CLASS retention and is not visible reflectively, so asserting on it would prove nothing.
     */
    @Test
    void generatedDescriptorBindsTheGoalToTheVerifyPhase() throws IOException {
        String descriptor = Files.readString(Path.of(System.getProperty("pluginDescriptor")));

        assertTrue(descriptor.contains("<goal>check</goal>"), descriptor);
        assertTrue(descriptor.contains("<phase>verify</phase>"), descriptor);
        assertTrue(descriptor.contains("<threadSafe>true</threadSafe>"), descriptor);
        assertTrue(descriptor.contains("<goalPrefix>static-allocation-checker</goalPrefix>"), descriptor);
        assertTrue(descriptor.contains(
                "<implementation>com.staticallocationchecker.maven.StaticAllocationCheckerMojo</implementation>"),
                descriptor);
    }

    @Test
    void generatedDescriptorDefaultsOutputDirectoryToTheProjectBuildOutput() throws IOException {
        String descriptor = Files.readString(Path.of(System.getProperty("pluginDescriptor")));

        assertTrue(descriptor.contains("default-value=\"${project.build.outputDirectory}\""), descriptor);
    }

    @Test
    void passesWhenThereAreNoFindings(@TempDir Path dir) throws Exception {
        assertDoesNotThrow(() -> mojoFor(dir).execute());
    }

    @Test
    void failsTheBuildWhenAnAllocationIsFound(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("com/staticallocationchecker/fixtures");
        Files.createDirectories(classes);
        copyFixtureTo(classes.resolve("DirectNew.class"));

        MojoFailureException thrown = assertThrows(MojoFailureException.class, mojoFor(dir)::execute);

        assertTrue(thrown.getMessage().contains("finding"), thrown.getMessage());
    }

    @Test
    @Disabled("GAP: the mojo analyses only ${project.build.outputDirectory} and exposes no "
            + "parameters at all - no skip flag, no additional roots, no way to treat findings as "
            + "warnings. A missing output directory throws UncheckedIOException from deep inside "
            + "the checker rather than a MojoExecutionException Maven can report cleanly")
    void reportsAMissingOutputDirectoryAsAMavenFailure(@TempDir Path parent) throws Exception {
        StaticAllocationCheckerMojo mojo = mojoFor(parent.resolve("nonexistent"));

        assertThrows(MojoFailureException.class, mojo::execute,
                "an internal UncheckedIOException is not a usable build error");
    }

    private static void copyFixtureTo(Path target) throws IOException {
        Path source = Path.of(System.getProperty("fixtureClasses"))
                .resolve("com/staticallocationchecker/fixtures/DirectNew.class");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

}
