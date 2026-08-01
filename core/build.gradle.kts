plugins {
    `java-library`
}

val asmVersion = "9.7.1"

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-analysis:$asmVersion")
}

java {
    withJavadocJar()
    withSourcesJar()
}

// The agent can only really be tested by launching a JVM with -javaagent, which needs the built
// jar. That is a different dependency shape from the unit tests, so it gets its own source set.
val agentTest: SourceSet by sourceSets.creating

configurations["agentTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["agentTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "agentTestImplementation"(project)
}

val agentTestTask = tasks.register<Test>("agentTest") {
    group = "verification"
    description = "Runs the java agent end-to-end in a forked JVM launched with -javaagent."
    testClassesDirs = agentTest.output.classesDirs
    classpath = agentTest.runtimeClasspath
    useJUnitPlatform()

    val jarTask = tasks.jar
    dependsOn(jarTask)
    // The harness runs with ONLY its own classes on the classpath: everything else must come from
    // the agent jar, which the JVM appends to the system class path.
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-DagentJar=${jarTask.get().archiveFile.get().asFile.absolutePath}",
            "-DharnessClasses=${agentTest.output.classesDirs.singleFile.absolutePath}",
        )
    })
}

tasks.check {
    dependsOn(agentTestTask)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.staticallocationchecker.instrument.AllocationFlightAgent",
            "Agent-Class" to "com.staticallocationchecker.instrument.AllocationFlightAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
