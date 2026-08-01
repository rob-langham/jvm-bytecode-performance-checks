package com.staticallocationchecker.maven;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Report;
import java.io.File;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal that runs the {@link AllocationChecker} over the module's compiled classes.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class StaticAllocationCheckerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoFailureException {
        Report report = new AllocationChecker().analyze(List.of(outputDirectory.toPath()), List.of());
        report.findings().forEach(finding -> getLog().warn("static-allocation-checker: " + finding));
        if (!report.isClean()) {
            throw new MojoFailureException(report.findings().size() + " static allocation finding(s)");
        }
        getLog().info("static-allocation-checker: no findings");
    }
}
