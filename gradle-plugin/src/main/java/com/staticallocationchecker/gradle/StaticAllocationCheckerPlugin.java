package com.staticallocationchecker.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Gradle plugin that registers the static allocation checker task.
 */
public class StaticAllocationCheckerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getTasks().register("checkStaticAllocation", StaticAllocationCheckerTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the static allocation checker.");
        });
    }
}
