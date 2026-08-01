plugins {
    java
    id("io.github.rob-langham.static-allocation-checker")
}

dependencies {
    implementation("io.github.rob-langham:core:${project.extra["checkerVersion"]}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
