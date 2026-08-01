// Each scenario carries its own complete, readable build file - that is half of what these demos
// are for. This root build only holds what is genuinely shared.
plugins {
    id("com.staticallocationchecker.static-allocation-checker") apply false
}

val demoVersion = "0.1.0-SNAPSHOT"

subprojects {
    repositories {
        mavenCentral()
    }
    extra["checkerVersion"] = demoVersion
}

/**
 * Runs every scenario and prints each one's findings, without stopping at the first that fails.
 * The scenarios that are supposed to produce findings set `ignoreFailures`, so a red build here
 * means something is actually wrong.
 */
tasks.register("demo") {
    group = "verification"
    description = "Runs every demo scenario and prints its findings."
    dependsOn(subprojects.map { "${it.path}:checkStaticAllocation" })

    doLast {
        println()
        println("=".repeat(78))
        println("DEMO RESULTS")
        println("=".repeat(78))
        subprojects.sortedBy { it.name }.forEach { scenario ->
            val report = scenario.layout.buildDirectory
                .file("reports/static-allocation-checker/findings.txt").get().asFile
            val findings = if (report.exists()) {
                report.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            println()
            println("── ${scenario.name} ".padEnd(78, '─'))
            if (findings.isEmpty()) {
                println("   no findings")
            } else {
                findings.forEach { println("   $it") }
            }
        }
        println()
    }
}
