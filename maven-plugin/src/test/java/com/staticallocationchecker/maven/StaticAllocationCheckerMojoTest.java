package com.staticallocationchecker.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Maven goal's binding and its pass/fail behaviour. */
class StaticAllocationCheckerMojoTest {

    private StaticAllocationCheckerMojo mojoFor(Path outputDirectory) throws Exception {
        StaticAllocationCheckerMojo mojo = new StaticAllocationCheckerMojo();
        set(mojo, "outputDirectory", outputDirectory.toFile());
        return mojo;
    }

    private static void set(StaticAllocationCheckerMojo mojo, String name, Object value)
            throws Exception {
        Field field = StaticAllocationCheckerMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
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
    void generatedDescriptorExposesTheUserFacingParameters() throws IOException {
        String descriptor = Files.readString(Path.of(System.getProperty("pluginDescriptor")));

        // Maven only honours what reaches the descriptor, so the parameters are asserted there
        // rather than on the fields that declare them.
        assertTrue(descriptor.contains("<name>skip</name>"), descriptor);
        assertTrue(descriptor.contains("<name>ignoreFailures</name>"), descriptor);
        assertTrue(descriptor.contains("<name>additionalRoots</name>"), descriptor);
        assertTrue(descriptor.contains("<name>resolveClasspath</name>"), descriptor);
        assertTrue(descriptor.contains("static-allocation-checker.skip"),
                "the skip flag needs a -D property to be usable from the command line");
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
    void reportsAMissingOutputDirectoryAsAnExecutionFailure(@TempDir Path parent) throws Exception {
        StaticAllocationCheckerMojo mojo = mojoFor(parent.resolve("nonexistent"));

        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute,
                "the check could not run; that is an execution failure, not a clean result");

        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
    }

    @Test
    void skipFlagBypassesTheGoalEntirely(@TempDir Path parent) throws Exception {
        StaticAllocationCheckerMojo mojo = mojoFor(parent.resolve("nonexistent"));
        set(mojo, "skip", true);

        assertDoesNotThrow(mojo::execute, "skip must win over every other consideration");
    }

    @Test
    void ignoreFailuresDowngradesFindingsToWarnings(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("com/staticallocationchecker/fixtures");
        Files.createDirectories(classes);
        copyFixtureTo(classes.resolve("DirectNew.class"));
        StaticAllocationCheckerMojo mojo = mojoFor(dir);
        set(mojo, "ignoreFailures", true);

        assertDoesNotThrow(mojo::execute, "adoption on an existing codebase needs a way in");
    }

    @Test
    void additionalRootsAreAnalysedAlongsideTheModuleOutput(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty");
        Path extra = dir.resolve("extra/com/staticallocationchecker/fixtures");
        Files.createDirectories(empty);
        Files.createDirectories(extra);
        copyFixtureTo(extra.resolve("DirectNew.class"));
        StaticAllocationCheckerMojo mojo = mojoFor(empty);
        set(mojo, "additionalRoots", List.of(dir.resolve("extra").toFile()));

        assertThrows(MojoFailureException.class, mojo::execute,
                "a finding in an additional root must still fail the build");
    }

    private static void copyFixtureTo(Path target) throws IOException {
        Path source = Path.of(System.getProperty("fixtureClasses"))
                .resolve("com/staticallocationchecker/fixtures/DirectNew.class");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

}
