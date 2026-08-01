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
