// A standalone build, deliberately NOT part of the root project's reactor: it is not listed in
// ../settings.gradle.kts, and building the library never builds these.
//
// It consumes the checker the same way any other project would, except that the artifacts come
// from the sibling build instead of a repository. `includeBuild` is what makes that substitution
// happen - swap it for a normal version once the plugin is published, and nothing else changes.
pluginManagement {
    // Makes the plugin id resolvable from the sibling build.
    includeBuild("..")
    repositories {
        gradlePluginPortal()
    }
}

// Included again at the top level, which is what substitutes the `com.staticallocationchecker:core`
// dependency for the sibling build's project. The pluginManagement include above covers plugin
// resolution only - the two are separate mechanisms.
includeBuild("..")

rootProject.name = "static-allocation-checker-demos"

include(
    "01-zero-allocation-basics",
    "02-clean-hot-path",
    "03-warmup-contract",
    "04-dispatch-and-inheritance",
    "05-varargs",
    "06-conflicting-contracts",
    "07-runtime-flight-recorder",
)
