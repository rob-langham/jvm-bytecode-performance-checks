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

// ---------------------------------------------------------------------------------------------
// The cross-release fixture matrix.
//
// The support policy is bytecode from 8 to 25, and the whole point of a category-keyed finding is
// that it says the same thing whatever release the code was compiled at. Nothing proved that: the
// checked-in fixtures are all compiled at one level, so a regression that only showed up on
// release-8 (or release-25) bytecode would pass every test.
//
// So the same fixture source is compiled once per level and analysed once per level. All of them
// are compiled by ONE javac - a Java 25 one - because javac 17 (the build's toolchain) cannot emit
// --release 21 or 25, while a javac 25 covers the whole range down to 8. The toolchain is
// requested rather than assumed; the Foojay resolver in settings.gradle.kts downloads it if the
// machine has not got one.
// ---------------------------------------------------------------------------------------------
val crossReleaseLevels = listOf(8, 11, 17, 21, 25)

/** Records, and so this fixture, need 17. */
val recordCapableLevels = crossReleaseLevels.filter { it >= 17 }

val crossReleaseCompiler = javaToolchains.compilerFor {
    languageVersion = JavaLanguageVersion.of(25)
}

fun registerCrossReleaseCompilation(name: String, sourceDir: String, levels: List<Int>) =
    levels.associateWith { level ->
        tasks.register<JavaCompile>("compile${name}Fixtures$level") {
            group = "build"
            description = "Compiles the $sourceDir fixtures at --release $level."
            javaCompiler = crossReleaseCompiler
            // A SourceTask's source is a tracked input, so editing a fixture reruns the compile -
            // and, through the test task's dependency on this output, reruns the assertions about
            // it. A test that stayed up-to-date while its subject changed has bitten this build
            // before; the fix is to declare the wiring, not to remember to run --rerun-tasks.
            source = fileTree(layout.projectDirectory.dir(sourceDir))
            include("**/*.java")
            classpath = files(annotations.output.classesDirs)
            destinationDirectory = layout.buildDirectory.dir("crossReleaseFixtures/$name/$level")
            options.release = level
            // javac 25 warns that source 8 is obsolete. It is, and that is the point of the row.
            options.compilerArgs.add("-Xlint:-options")
            dependsOn(annotations.classesTaskName)
        }
    }

val crossReleaseFixtureTasks =
    registerCrossReleaseCompilation("CrossRelease", "src/crossReleaseFixtures/java", crossReleaseLevels)
val crossReleaseRecordFixtureTasks =
    registerCrossReleaseCompilation(
        "CrossReleaseRecord", "src/crossReleaseFixtures/java17", recordCapableLevels)

tasks.test {
    dependsOn(crossReleaseFixtureTasks.values, crossReleaseRecordFixtureTasks.values)
    // The compiled fixtures are an input to the test itself, not just something it happens to
    // depend on: the assertions are about their bytecode, so a change to them must invalidate the
    // test's result.
    inputs.files(crossReleaseFixtureTasks.values.map { it.map { task -> task.destinationDirectory } })
        .withPropertyName("crossReleaseFixtureClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        crossReleaseRecordFixtureTasks.values.map { it.map { task -> task.destinationDirectory } })
        .withPropertyName("crossReleaseRecordFixtureClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Handed over as one system property per level, following the fixtureClasses/agentJar pattern
    // used by the agent tests and the Gradle plugin's tests.
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        crossReleaseFixtureTasks.map { (level, task) ->
            "-DcrossReleaseFixtures.$level=" +
                task.get().destinationDirectory.get().asFile.absolutePath
        } + crossReleaseRecordFixtureTasks.map { (level, task) ->
            "-DcrossReleaseRecordFixtures.$level=" +
                task.get().destinationDirectory.get().asFile.absolutePath
        }
    })
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
