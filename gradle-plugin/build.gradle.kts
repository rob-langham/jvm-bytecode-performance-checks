plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":core"))
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
