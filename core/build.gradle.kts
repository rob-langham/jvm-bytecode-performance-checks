plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

// 9.7.1 refused class-file major 69, so anything compiled by a Java 25 javac was unreadable.
// Bytecode support only ever arrives in a newer ASM, so this pin is the checker's real ceiling.
val asmVersion = "9.10.1"

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-analysis:$asmVersion")
}

// ---------------------------------------------------------------------------------------------
// The annotations are compiled at release 8, everything else at 17.
//
// A project building with `--release 8` cannot have a major-61 class file on its compile
// classpath: javac rejects the whole jar, not just the class. The annotations are the only part
// of this library a checked project actually compiles against, so if they were major 61 the tool
// would be unusable for exactly the oldest bytecode it claims to analyse. They are two bare
// @interface declarations with nothing in them that Java 8 lacks, so compiling them at 8 costs
// nothing.
//
// They live in their own source set for that reason alone. The package is unchanged - the checker
// matches the annotations by descriptor string (see ZERO_ALLOCATIONS in AllocationChecker), so
// moving the binary names would break it silently - and their classes are folded back into main's
// output, so the library jar, the agent jar and every consumer see one artifact as before.
val annotations: SourceSet by sourceSets.creating

tasks.named<JavaCompile>(annotations.compileJavaTaskName) {
    options.release = 8
}

val mainOutput = sourceSets.main.get().output

// classesDirs rather than output.dir(): only classesDirs is what the `classes` variant publishes,
// and that variant is what a composite build compiles against. Adding them anywhere else would
// work for the jar and fail for `includeBuild`, which is how the demos consume this.
(mainOutput.classesDirs as ConfigurableFileCollection).from(annotations.output.classesDirs)
tasks.named("classes") { dependsOn(annotations.classesTaskName) }

sourceSets.main { compileClasspath += annotations.output }

// The sources and javadoc jars Central requires are added by the publish plugin, so they are
// deliberately not declared here - declaring both produces two artifacts with the same
// classifier and the publication is rejected. They are built from main's sources, which no longer
// include the annotations, so those are added back.
tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
    from(annotations.allSource)
}
tasks.withType<Javadoc>().configureEach {
    source(annotations.allJava)
    classpath += annotations.output
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
