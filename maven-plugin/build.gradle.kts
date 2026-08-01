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
