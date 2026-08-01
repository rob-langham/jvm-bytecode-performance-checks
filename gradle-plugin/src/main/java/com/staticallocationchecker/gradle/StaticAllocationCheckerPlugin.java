package com.staticallocationchecker.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Gradle plugin that registers the static allocation checker task.
 *
 * <p>Defaults come from the main source set rather than a hardcoded path, so non-default layouts
 * and non-Java JVM languages work without configuration.
 */
public class StaticAllocationCheckerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        TaskProvider<StaticAllocationCheckerTask> checkTask = project.getTasks().register(
                "checkStaticAllocation", StaticAllocationCheckerTask.class, task -> {
                    task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                    task.setDescription("Runs the static allocation checker.");
                    task.getIgnoreFailures().convention(false);
                    task.getReportFile().convention(project.getLayout().getBuildDirectory()
                            .file("reports/static-allocation-checker/findings.txt"));
                });

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class)
                    .getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            checkTask.configure(task -> {
                task.getClassesDirs().convention(main.getOutput().getClassesDirs());
                task.dependsOn(main.getClassesTaskName());
            });
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                    .configure(check -> check.dependsOn(checkTask));
        });
    }
}
