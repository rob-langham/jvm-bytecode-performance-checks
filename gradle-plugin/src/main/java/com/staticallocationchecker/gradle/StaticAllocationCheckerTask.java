package com.staticallocationchecker.gradle;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.Report;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Task that runs the {@link AllocationChecker} over the project's compiled classes.
 *
 * <p>Declares its inputs and outputs, so Gradle can skip it when nothing has changed rather than
 * re-analysing on every build. A configured directory that does not exist is an error rather than
 * an empty analysis: reporting a clean bill of health for code that was never read is the failure
 * this tool exists to prevent.
 */
public abstract class StaticAllocationCheckerTask extends DefaultTask {

    /** The compiled classes to analyse. Defaults to the main source set's output. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassesDirs();

    /** Additional roots used only to resolve callees, widening what can be analysed. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getResolveClasspath();

    /**
     * The Java release the analysed code will run on, used to resolve multi-release jars (JEP 238)
     * the way the running JVM will.
     *
     * <p>A multi-release dependency carries the same class more than once - a base copy, and a copy
     * under {@code META-INF/versions/N/} for JVMs at release {@code N} or above - and the copies are
     * not equivalent. Analysing the base copy of a jar whose modern copy is the one that actually
     * runs reports on code the application never executes, and an allocation added in the modern
     * copy goes unseen.
     *
     * <p>The convention is the project's own compile target: {@code compileJava}'s
     * {@code options.release} if it is set, otherwise the java extension's target compatibility -
     * which itself follows the toolchain. That is the right default because it is where "which JVM
     * is this built for" already lives, and it is the answer for the overwhelmingly common case of
     * an application built for the JVM it runs on.
     *
     * <p>Override it when those differ - an application compiled at release 8 for compatibility but
     * deployed on a 21 JVM should set {@code targetRelease = 21}, because 21 is the release whose
     * versioned entries will be loaded:
     *
     * <pre>{@code
     * tasks.named<StaticAllocationCheckerTask>("checkStaticAllocation") {
     *     targetRelease.set(21)
     * }
     * }</pre>
     *
     * <p>Unset - which is what happens when the java plugin is not applied - means base entries
     * only, the behaviour of every release before this property existed.
     */
    @Input
    @Optional
    public abstract Property<Integer> getTargetRelease();

    /** When true, findings are logged but do not fail the build. Defaults to false. */
    @Input
    public abstract Property<Boolean> getIgnoreFailures();

    /** Where findings are written. Optional, but makes the task's result reviewable and cacheable. */
    @OutputFile
    @Optional
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void check() {
        List<Path> roots = analysisRoots();
        List<Path> resolveClasspath = new ArrayList<>();
        for (File entry : getResolveClasspath()) {
            resolveClasspath.add(entry.toPath());
        }

        Report report;
        try {
            report = new AllocationChecker(getTargetRelease().getOrElse(0))
                    .analyze(roots, resolveClasspath);
        } catch (RuntimeException e) {
            // The checker throws unchecked when it cannot read what it was pointed at, and its
            // message is the actionable part. Letting that escape raw makes Gradle print an
            // internal stack trace with the guidance buried in it; GradleException is Gradle's
            // word for "this task could not do its job", which is exactly what happened.
            throw new GradleException("static-allocation-checker could not analyse "
                    + getClassesDirs().getFiles() + ": " + e.getMessage(), e);
        }
        report.findings().forEach(finding -> getLogger().warn("static-allocation-checker: {}", finding));
        writeReport(report);

        if (report.isClean()) {
            getLogger().lifecycle("static-allocation-checker: no findings");
            return;
        }
        String summary = report.findings().size() + " static allocation finding(s)";
        if (getIgnoreFailures().getOrElse(false)) {
            getLogger().warn("static-allocation-checker: {} (ignoreFailures is set)", summary);
            return;
        }
        throw new GradleException(summary);
    }

    private List<Path> analysisRoots() {
        List<Path> roots = new ArrayList<>();
        for (File dir : getClassesDirs()) {
            if (!dir.exists()) {
                throw new GradleException("static-allocation-checker: configured classes directory does"
                        + " not exist: " + dir + ". Nothing would be analysed, and passing silently"
                        + " would report code as clean that was never read.");
            }
            roots.add(dir.toPath());
        }
        if (roots.isEmpty()) {
            throw new GradleException("static-allocation-checker: no classes directories configured,"
                    + " so nothing would be analysed. Set classesDirs explicitly.");
        }
        return roots;
    }

    private void writeReport(Report report) {
        if (!getReportFile().isPresent()) {
            return;
        }
        Path target = getReportFile().get().getAsFile().toPath();
        StringBuilder text = new StringBuilder();
        for (Finding finding : report.findings()) {
            text.append(finding).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }
}
