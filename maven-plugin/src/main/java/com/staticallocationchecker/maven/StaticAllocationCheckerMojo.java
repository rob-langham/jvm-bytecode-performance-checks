package com.staticallocationchecker.maven;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Report;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal that runs the {@link AllocationChecker} over the module's compiled classes.
 *
 * <p>Distinguishes the two Maven failure kinds deliberately: a {@link MojoFailureException} means
 * the check ran and found something, a {@link MojoExecutionException} means the check could not run
 * at all. Collapsing the second into "no findings" would report code as clean that was never read.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class StaticAllocationCheckerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    /** Skips the goal entirely. */
    @Parameter(property = "static-allocation-checker.skip", defaultValue = "false")
    private boolean skip;

    /** Reports findings without failing the build, for adoption on an existing codebase. */
    @Parameter(property = "static-allocation-checker.ignoreFailures", defaultValue = "false")
    private boolean ignoreFailures;

    /**
     * Additional directories or jars to analyse alongside this module's own output, letting a
     * multi-module build resolve calls that would otherwise be reported unanalyzable.
     */
    @Parameter
    private List<File> additionalRoots;

    /** Roots used only to resolve callees, never scanned for annotated entry points. */
    @Parameter
    private List<File> resolveClasspath;

    /**
     * The Java release the analysed code will run on, used to resolve multi-release jars (JEP 238)
     * the way the running JVM will.
     *
     * <p>Defaults to {@code ${maven.compiler.release}}, which is where a Maven build already states
     * which JVM it builds for. Left absent - the property unset, as it is in a build that still
     * uses {@code maven.compiler.source}/{@code target} - only the base entries of a multi-release
     * jar are read, which is what this goal did before the parameter existed. Set it explicitly
     * when the deployment JVM is newer than the compile target.
     */
    @Parameter(property = "static-allocation-checker.targetRelease",
            defaultValue = "${maven.compiler.release}")
    private String targetRelease;

    @Override
    public void execute() throws MojoFailureException, MojoExecutionException {
        if (skip) {
            getLog().info("static-allocation-checker: skipped");
            return;
        }

        Report report;
        try {
            report = new AllocationChecker(targetRelease())
                    .analyze(roots(), paths(resolveClasspath));
        } catch (RuntimeException e) {
            // The checker throws unchecked when it cannot read what it was pointed at. Surfacing
            // that as an internal stack trace helps nobody; Maven has a word for "the plugin could
            // not do its job", and this is it.
            throw new MojoExecutionException(
                    "static-allocation-checker could not analyse " + outputDirectory + ": "
                            + e.getMessage(), e);
        }

        report.findings().forEach(finding -> getLog().warn("static-allocation-checker: " + finding));
        if (report.isClean()) {
            getLog().info("static-allocation-checker: no findings");
            return;
        }
        String summary = report.findings().size() + " static allocation finding(s)";
        if (ignoreFailures) {
            getLog().warn("static-allocation-checker: " + summary + " (ignoreFailures is set)");
            return;
        }
        throw new MojoFailureException(summary);
    }

    /**
     * The configured release, or 0 for "base entries only".
     *
     * <p>Absent and empty both mean unset: {@code ${maven.compiler.release}} resolves to an empty
     * string in a build that never defined the property. Anything else that is not a release number
     * is a configuration mistake and is reported as one - quietly falling back to base-only would
     * analyse a different jar than the user asked for and still say the build was checked.
     */
    private int targetRelease() throws MojoExecutionException {
        if (targetRelease == null || targetRelease.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(targetRelease.trim());
        } catch (NumberFormatException e) {
            throw new MojoExecutionException("static-allocation-checker: targetRelease must be a "
                    + "Java release number such as 8, 17 or 21, but was: " + targetRelease, e);
        }
    }

    private List<Path> roots() throws MojoExecutionException {
        if (!outputDirectory.exists()) {
            throw new MojoExecutionException("static-allocation-checker: output directory does not "
                    + "exist: " + outputDirectory + ". Nothing would be analysed, and passing "
                    + "silently would report code as clean that was never read. Bind the goal "
                    + "after compile, or set static-allocation-checker.skip for modules with no code.");
        }
        List<Path> roots = new ArrayList<>();
        roots.add(outputDirectory.toPath());
        roots.addAll(paths(additionalRoots));
        return roots;
    }

    private static List<Path> paths(List<File> files) {
        List<Path> paths = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                paths.add(file.toPath());
            }
        }
        return paths;
    }
}
