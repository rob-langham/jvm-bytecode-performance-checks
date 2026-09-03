package com.staticallocationchecker.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
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
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            checkTask.configure(task -> {
                task.getClassesDirs().convention(main.getOutput().getClassesDirs());
                task.getTargetRelease().convention(compileTargetOf(project, java, main));
                task.dependsOn(main.getClassesTaskName());
            });
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                    .configure(check -> check.dependsOn(checkTask));
        });
    }

    /**
     * The release this project compiles for, as the convention for {@code targetRelease}.
     *
     * <p>{@code options.release} first, because when it is set it is the definitive statement of
     * the target; the java extension's target compatibility second, which is where a toolchain's
     * language version ends up when nothing more specific was said. Both are read lazily - a
     * buildscript that configures either after the plugin is applied still wins.
     */
    private static Provider<Integer> compileTargetOf(
            Project project, JavaPluginExtension java, SourceSet main) {
        Provider<Integer> declaredRelease = project.getTasks()
                .named(main.getCompileJavaTaskName(), JavaCompile.class)
                .flatMap(compile -> compile.getOptions().getRelease());
        return declaredRelease.orElse(project.provider(
                () -> Integer.valueOf(java.getTargetCompatibility().getMajorVersion())));
    }
}
