plugins {
    java
    id("de.benediktritter.maven-plugin-development") version "0.4.3"
}

val mavenApiVersion = "3.9.9"

dependencies {
    implementation(project(":core"))
    implementation("org.apache.maven:maven-plugin-api:$mavenApiVersion")
    implementation("org.apache.maven:maven-core:$mavenApiVersion")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")
}

mavenPlugin {
    goalPrefix.set("static-allocation-checker")
}

// The mojo tests copy compiled fixtures out of :core's test output to build a synthetic module.
val coreTestClasses = project(":core").layout.buildDirectory.dir("classes/java/test")

// @Mojo has CLASS retention, so the goal's binding can only be verified through the descriptor
// that maven-plugin-tools generates - which is also what Maven itself consumes.
val descriptorFile = layout.buildDirectory.file("mavenPlugin/descriptor/META-INF/maven/plugin.xml")

tasks.test {
    dependsOn(":core:testClasses", "generateMavenPluginDescriptor")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-DfixtureClasses=${coreTestClasses.get().asFile.absolutePath}",
            "-DpluginDescriptor=${descriptorFile.get().asFile.absolutePath}",
        )
    })
}
