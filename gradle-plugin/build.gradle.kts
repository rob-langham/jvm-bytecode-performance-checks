plugins {
    `java-gradle-plugin`
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
    plugins {
        create("staticAllocationChecker") {
            id = "com.staticallocationchecker.static-allocation-checker"
            implementationClass = "com.staticallocationchecker.gradle.StaticAllocationCheckerPlugin"
            displayName = "Static Allocation Checker"
            description = "Checks static allocation constraints as part of the Gradle build."
        }
    }
}
