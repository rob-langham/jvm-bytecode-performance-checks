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

tasks.named<JavaExec>("run") {
    dependsOn(agentJar)
    jvmArgs("-javaagent:${agentJarFile()}")
}

/**
 * Runs the driver and checks the *shape* of the result, not just that it exited cleanly.
 *
 * <p>This scenario's entire claim is that one site stops allocating and the other does not. If the
 * agent silently stopped instrumenting - which is exactly how it failed once before, by loading no
 * ASM and swallowing every error - the program would still run happily and print zeroes. Only
 * asserting on the numbers catches that.
 */
tasks.register<JavaExec>("verifyRun") {
    group = "verification"
    description = "Runs the load driver and verifies the recorder actually recorded."
    dependsOn(agentJar)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "demo.runtime.LoadDriver"
    jvmArgs("-javaagent:${agentJarFile()}")

    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doLast {
        val output = captured.toString()
        println(output)

        val problems = mutableListOf<String>()
        if (!output.contains("PricingEngine#levels=1")) {
            problems += "PricingEngine should allocate exactly once and hold at 1"
        }
        // The last round must show the resizing cache still climbing well past the engine's 1.
        val finalRound = output.lines().lastOrNull { line -> line.trimStart().startsWith("6 ") }
        val climbing = Regex("ResizingCache#scratch=(\\d+)").find(finalRound.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (climbing < 100) {
            problems += "ResizingCache should still be climbing by round 6, saw $climbing"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The runtime demo no longer demonstrates what its README claims:\n  " +
                    problems.joinToString("\n  ") +
                    "\n\nA recorder reporting nothing looks identical to a clean program.")
        }
        println("Runtime demo verified: one site converged, the other did not.")
    }
}

// The two classes here are statically indistinguishable, and that is the whole point of this
// scenario - so the static check passes and the interesting output comes from `run`.
