plugins {
    // Publishes to Maven Central through the Central Portal, and handles signing plus the
    // sources/javadoc jars Central requires. Applied per-module, not here.
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

// The group is io.github.rob-langham rather than com.staticallocationchecker because Maven Central
// and the Gradle Plugin Portal both require proof that you own the namespace. io.github.<user> is
// verified from the GitHub account itself; a com.* group would need a domain we do not own.
//
// Java packages deliberately stay com.staticallocationchecker.*: the group and the package name do
// not have to match, and renaming them would mean touching the annotation descriptors the checker
// matches on and the agent's Premain-Class - places where a typo fails silently.
allprojects {
    group = "io.github.rob-langham"
    version = "0.1.0"
}

subprojects {
    repositories {
        mavenCentral()
    }

    // Shared Java configuration applied to any subproject that uses the Java plugin.
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Publishing
//
// Central rejects a POM missing any of name, description, url, licence, developer or SCM - and it
// rejects it at upload time, not at build time. Configuring it once here rather than three times
// keeps the three modules from drifting apart in ways nobody notices until a release fails.
// ---------------------------------------------------------------------------------------------

val moduleDescriptions = mapOf(
    "core" to "Static bytecode analysis enforcing zero-allocation contracts on JVM hot paths, "
        + "with a runtime allocation flight recorder exposed over JMX.",
    "gradle-plugin" to "Gradle plugin for the JVM static allocation checker.",
    "maven-plugin" to "Maven plugin for the JVM static allocation checker.",
)

val projectUrl = "https://github.com/rob-langham/jvm-bytecode-performance-checks"

configure(subprojects.filter { it.name in moduleDescriptions.keys }) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)

        // Central requires signed artifacts. Signing only when a key is present keeps
        // `publishToMavenLocal` usable without a GPG setup; the release workflow supplies the key,
        // and a release without one fails at upload rather than shipping unsigned.
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }

        pom {
            name.set(project.name)
            description.set(moduleDescriptions.getValue(project.name))
            url.set(projectUrl)
            inceptionYear.set("2026")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("rob-langham")
                    name.set("Robert Langham")
                    url.set("https://github.com/rob-langham")
                }
            }
            scm {
                url.set(projectUrl)
                connection.set("scm:git:$projectUrl.git")
                developerConnection.set("scm:git:ssh://git@github.com/rob-langham/jvm-bytecode-performance-checks.git")
            }
        }
    }
}
