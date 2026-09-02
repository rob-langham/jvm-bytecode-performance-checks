// The build pins a Java 17 toolchain (build.gradle.kts), which Gradle will not invent for itself:
// on a machine with only a newer JDK it fails with "No locally installed toolchains match", which
// reads like a broken build rather than a missing download. The Foojay resolver lets Gradle fetch
// 17 instead, so `./gradlew build` works on whatever JDK a contributor - or a CI matrix job -
// happens to be running.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "static-allocation-checker"

include(
    "core",
    "maven-plugin",
    "gradle-plugin",
)
