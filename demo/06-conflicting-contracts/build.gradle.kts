plugins {
    java
    id("com.staticallocationchecker.static-allocation-checker")
}

dependencies {
    implementation("com.staticallocationchecker:core:${project.extra["checkerVersion"]}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// This scenario exists to show findings, so it must not fail its own build.
// A real project would leave this alone and let the build break.
tasks.checkStaticAllocation {
    ignoreFailures.set(true)
}
