plugins {
    java
    application
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

application {
    mainClass = "demo.runtime.LoadDriver"
}

// The agent is a separate, shaded artifact: a -javaagent jar is appended to the system class path
// with nothing beside it, so it has to carry its own dependencies. Building it here through the
// included build keeps `./gradlew run` a single command.
val agentJar = tasks.register<Exec>("buildAgentJar") {
    group = "build"
    description = "Builds the shaded agent jar in the sibling library build."
    workingDir = rootDir.parentFile
    commandLine("./gradlew", "--quiet", ":core:shadowJar")
    outputs.file(agentJarFile())
}

fun agentJarFile() = rootDir.parentFile
    .resolve("core/build/libs/core-0.1.0-SNAPSHOT-agent.jar")

tasks.named<JavaExec>("run") {
    dependsOn(agentJar)
    jvmArgs("-javaagent:${agentJarFile()}")
}

// The two classes here are statically indistinguishable, and that is the whole point of this
// scenario - so the static check passes and the interesting output comes from `run`.
