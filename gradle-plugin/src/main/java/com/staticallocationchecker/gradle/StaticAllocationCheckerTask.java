package com.staticallocationchecker.gradle;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Report;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

/**
 * Task that runs the {@link AllocationChecker} over the project's compiled main classes.
 */
public class StaticAllocationCheckerTask extends DefaultTask {

    @TaskAction
    public void check() {
        Path classes = getProject().getLayout().getBuildDirectory()
                .dir("classes/java/main").get().getAsFile().toPath();
        Report report = new AllocationChecker().analyze(List.of(classes), List.of());
        report.findings().forEach(finding -> getLogger().warn("static-allocation-checker: {}", finding));
        if (!report.isClean()) {
            throw new GradleException(report.findings().size() + " static allocation finding(s)");
        }
        getLogger().lifecycle("static-allocation-checker: no findings");
    }
}
