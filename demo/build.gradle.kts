// Each scenario carries its own complete, readable build file - that is half of what these demos
// are for. This root build only holds what is genuinely shared.
plugins {
    id("io.github.rob-langham.static-allocation-checker") apply false
}

val demoVersion = "0.1.0"

subprojects {
    repositories {
        mavenCentral()
    }
    extra["checkerVersion"] = demoVersion
}

/**
 * How many findings each scenario should produce, read from the `expected-findings.txt` that sits
 * beside it, or null when the file is missing - which the demo task treats as a failure, never as
 * "nothing to verify".
 *
 * <p>This count is the only verification the scenarios have: most of them set `ignoreFailures` so
 * they can show their output rather than halting the run, so without it a checker that silently
 * stopped finding anything would leave every demo passing and every README quietly wrong. That is
 * exactly what happened once before, when these files were deleted and this check degraded to a
 * no-op without anyone noticing - hence missing now means broken.
 */
fun expectedFindingCount(scenario: Project): Int? {
    val file = scenario.file("expected-findings.txt")
    if (!file.exists()) {
        return null
    }
    return file.readLines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
}

/**
 * Runs every scenario, prints its findings, and checks them against what its README claims.
 */
tasks.register("demo") {
    group = "verification"
    description = "Runs every demo scenario, prints its findings, and verifies them."
    dependsOn(subprojects.map { "${it.path}:checkStaticAllocation" })

    doLast {
        println()
        println("=".repeat(78))
        println("DEMO RESULTS")
        println("=".repeat(78))

        val wrong = mutableListOf<String>()
        subprojects.sortedBy { it.name }.forEach { scenario ->
            val report = scenario.layout.buildDirectory
                .file("reports/static-allocation-checker/findings.txt").get().asFile
            val findings = if (report.exists()) {
                report.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            val expected = expectedFindingCount(scenario)

            println()
            println("── ${scenario.name} ".padEnd(78, '─'))
            if (findings.isEmpty()) {
                println("   no findings")
            } else {
                findings.forEach { println("   $it") }
            }
            if (expected == null) {
                wrong += "${scenario.name}: no expected-findings.txt beside the scenario, so its " +
                    "output was not verified at all"
            } else if (findings.size != expected) {
                wrong += "${scenario.name}: expected $expected finding(s), got ${findings.size}"
            }
        }

        println()
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "Demo scenarios no longer match their READMEs:\n  " + wrong.joinToString("\n  ") +
                    "\n\nEither the checker changed behaviour or a scenario drifted. " +
                    "Fix whichever is wrong, and update the scenario's README.")
        }
        println("All ${subprojects.size} scenarios reported exactly what their READMEs claim.")
        println()
    }
}
