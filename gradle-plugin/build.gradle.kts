plugins {
    `java-gradle-plugin`
    // Publishes to the Gradle Plugin Portal. The portal is a separate destination from Maven
    // Central with its own credentials and its own namespace approval, so the plugin is published
    // to both: the portal for `plugins { id(...) }` resolution, Central for anyone resolving it
    // as an ordinary dependency.
    id("com.gradle.plugin-publish") version "1.3.0"
}

dependencies {
    implementation(project(":core"))
    testImplementation(gradleTestKit())
}

// The plugin tests copy compiled fixtures out of :core's test output to build a synthetic project.
val coreTestClasses = project(":core").layout.buildDirectory.dir("classes/java/test")

tasks.test {
    dependsOn(":core:testClasses")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-DfixtureClasses=${coreTestClasses.get().asFile.absolutePath}")
    })
}

gradlePlugin {
    website = "https://github.com/rob-langham/jvm-bytecode-performance-checks"
    vcsUrl = "https://github.com/rob-langham/jvm-bytecode-performance-checks.git"

    plugins {
        create("staticAllocationChecker") {
            // The id's namespace must be one the portal can verify you own. io.github.<user> is
            // approved from the GitHub account; a com.* id would need a domain.
            id = "io.github.rob-langham.static-allocation-checker"
            implementationClass = "com.staticallocationchecker.gradle.StaticAllocationCheckerPlugin"
            displayName = "JVM Static Allocation Checker"
            description = "Fails the build on heap allocation in methods annotated as " +
                "zero-allocation, following the call through overrides and inherited methods."
            tags = listOf("performance", "bytecode", "allocation", "latency", "verification")
        }
    }
}
