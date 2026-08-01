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
 * What each scenario's README promises it will report.
 *
 * <p>Most scenarios set `ignoreFailures` so they can show their output rather than halting the
 * run - which means a checker that silently stopped finding anything would leave every demo
 * passing and every README quietly wrong. Asserting the counts is what makes `demo` a real check
 * rather than a "did it exit zero" check.
 */
val expectedFindings = mapOf(
    "01-zero-allocation-basics" to 6,
    "02-clean-hot-path" to 0,
    "03-warmup-contract" to 2,
    "04-dispatch-and-inheritance" to 5,
    "05-varargs" to 2,
    "06-conflicting-contracts" to 1,
    "07-runtime-flight-recorder" to 0,
)

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
            val expected = expectedFindings[scenario.name]

            println()
            println("── ${scenario.name} ".padEnd(78, '─'))
            if (findings.isEmpty()) {
                println("   no findings")
            } else {
                findings.forEach { println("   $it") }
            }
            if (expected != null && findings.size != expected) {
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
