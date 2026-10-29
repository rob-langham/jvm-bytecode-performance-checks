import java.io.ByteArrayOutputStream

plugins {
    java
    application
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
    .resolve("core/build/libs/core-0.1.0-agent.jar")

// The agent writes its trace to build/allocation-trace while the driver runs. The report turns that
// trace into numbers and reads this build's classes to say which contract governs each site.
val traceDirectory = layout.buildDirectory.get().asFile.resolve("allocation-trace")

tasks.named<JavaExec>("run") {
    dependsOn(agentJar)
    jvmArgs("-javaagent:${agentJarFile()}=dir=$traceDirectory")
}

/**
 * Reports on the trace the last run wrote, and checks the *shape* of the result, not just that it
 * exited cleanly.
 *
 * <p>This scenario's entire claim is that one site settles and the other does not. If the agent
 * silently stopped instrumenting - which is exactly how it failed once before, by loading no ASM and
 * swallowing every error - the program would still run happily and the trace would be empty. Only
 * asserting on the report catches that.
 */
tasks.register<JavaExec>("verifyRun") {
    group = "verification"
    description = "Reports on the allocation trace from the last run and verifies its shape."
    dependsOn("run")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.staticallocationchecker.trace.TraceReport"
    args(traceDirectory.absolutePath, sourceSets["main"].output.classesDirs.singleFile.absolutePath)

    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doLast {
        val output = captured.toString()
        println(output)

        // One row per site in the summary table: code, count, first, last, state, class#method.
        val states = Regex("(?m)^\\d+\\s+\\d+\\s+[\\d.]+\\s+[\\d.]+\\s+(steady|STILL ALLOCATING)\\s+(\\S+)$")
            .findAll(output)
            .associate { match -> match.groupValues[2] to match.groupValues[1] }

        val problems = mutableListOf<String>()
        if (states["demo.runtime.PricingEngine#levels"] != "steady") {
            problems += "PricingEngine should settle; saw ${states["demo.runtime.PricingEngine#levels"]}"
        }
        if (states["demo.runtime.ResizingCache#scratch"] != "STILL ALLOCATING") {
            problems += "ResizingCache should still be allocating at the end; saw " +
                states["demo.runtime.ResizingCache#scratch"]
        }
        if (Regex("contract: @AllocationsForWarmup").findAll(output).count() < 2) {
            problems += "both sites should be governed by @AllocationsForWarmup in the bytecode"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The runtime demo no longer demonstrates what its README claims:\n  " +
                    problems.joinToString("\n  ") +
                    "\n\nA trace with no events looks identical to a clean program.")
        }
        println("Runtime demo verified: one site settled, the other did not.")
    }
}

// The two classes here are statically indistinguishable, and that is the whole point of this
// scenario - so the static check passes and the interesting output comes from `run`.
