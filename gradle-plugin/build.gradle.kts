plugins {
    `java-gradle-plugin`
    // Publishes to the Gradle Plugin Portal. The portal is a separate destination from Maven
    // Central with its own credentials and its own namespace approval, so the plugin is published
    // to both: the portal for `plugins { id(...) }` resolution, Central for anyone resolving it
    // as an ordinary dependency.
    id("com.gradle.plugin-publish") version "1.3.0"
    id("com.gradleup.shadow") version "8.3.5"
}

// ---------------------------------------------------------------------------------------------
// The plugin jar carries the checker and ASM inside it, relocated.
//
// A plugin's classes are loaded from the build's own buildscript classpath, which the build being
// checked can rewrite: a `resolutionStrategy.force("org.ow2.asm:asm:9.0")` anywhere in it applies
// to the plugin too. Forcing 9.0 was tried, and the plugin then fails to read ordinary class files
// with a message about a bad parse - the user has no reason to connect that to a line in their own
// buildscript, and no supported way to exempt the plugin from it. Relocating means the plugin never
// resolves org.ow2.asm from that classpath at all, so there is nothing left to force.
//
// `bundled` rather than `implementation`: what is compiled against must not also be declared as a
// runtime dependency, or the published POM would tell consumers to resolve the very artifacts the
// jar already contains - and Gradle would put an unrelocated ASM back on the classpath beside the
// relocated one.
val bundled: Configuration by configurations.creating

dependencies {
    bundled(project(":core"))
    compileOnly(project(":core"))
    testImplementation(project(":core"))
    testImplementation(gradleTestKit())
}

val shadedAsmPackage = "com.staticallocationchecker.gradle.shaded.asm"

tasks.shadowJar {
    // The shaded jar IS the plugin jar - not a classified extra - so that whatever resolves the
    // plugin (the portal, Central, or a composite build) gets the self-contained one.
    archiveClassifier.set("")
    configurations = listOf(bundled)
    relocate("org.objectweb.asm", shadedAsmPackage)
}

// The plain jar steps aside entirely. Disabled rather than deleted because `jar` is still what
// everything depends on, and a disabled task still runs the tasks it depends on.
tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}

// The outgoing variants carry task artifacts, not file names, so disabling `jar` is not enough:
// a composite build resolves the plugin as a project dependency through these variants, and they
// still pointed at the never-built plain jar - which is exactly how the demos consume the plugin.
// Re-point them so includeBuild, the portal and Central all serve the one shaded artifact.
listOf("apiElements", "runtimeElements").forEach { variantName ->
    configurations.named(variantName) {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// TestKit builds the plugin classpath from the runtime classpath, which no longer contains the
// checker now that it is compileOnly - and a classpath assembled from loose class directories
// would not be the relocated one either. Pointing it at the shaded jar means the TestKit tests run
// exactly the artifact that ships.
tasks.pluginUnderTestMetadata {
    pluginClasspath.setFrom(tasks.shadowJar)
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
