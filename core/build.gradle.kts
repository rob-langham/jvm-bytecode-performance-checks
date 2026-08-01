plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

val asmVersion = "9.7.1"

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-analysis:$asmVersion")
}

// The sources and javadoc jars Central requires are added by the publish plugin, so they are
// deliberately not declared here - declaring both produces two artifacts with the same
// classifier and the publication is rejected.

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

    val jarTask = tasks.shadowJar
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

// The agent jar and the library jar are different artifacts with different rules.
//
// The library jar is consumed by the build plugins, which resolve ASM as a normal transitive
// dependency, so it must stay a plain thin jar and must NOT advertise agent entry points it
// cannot honour.
//
// The agent jar is appended to the system class path by the JVM with nothing else alongside it,
// so it has to carry every class it touches at transform time - ASM included. Those classes are
// relocated, because an agent that put its own org.objectweb.asm on the system class path would
// collide with whatever version the host application already uses.
val shadedAsmPackage = "com.staticallocationchecker.shaded.asm"

tasks.shadowJar {
    archiveClassifier.set("agent")
    relocate("org.objectweb.asm", shadedAsmPackage)
    manifest {
        attributes(
            "Premain-Class" to "com.staticallocationchecker.instrument.AllocationFlightAgent",
            "Agent-Class" to "com.staticallocationchecker.instrument.AllocationFlightAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
