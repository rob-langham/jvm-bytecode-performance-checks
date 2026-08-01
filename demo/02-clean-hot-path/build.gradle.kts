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
