---
title: Setup Guide
nav_order: 2
---

# Setup Guide

{: .no_toc }

1. TOC
{:toc}

---

## Requirements

- **Java 8 or later to run the checker or the agent.** The `core` jar — analyser, classifier,
  runtime recorder and agent alike — is compiled at `--release 8`, so it runs on a JDK 8 build
  host and the flight recorder attaches to a legacy JDK 8 production JVM. The code it checks can
  be just as old or much newer: bytecode compiled for Java 8, 11, 17, 21 and 25 is supported — the
  policy is 8, 11, and then every LTS. The hard ceiling is whatever class-file version the bundled
  ASM (currently 9.10.1) understands; a class file newer than that fails the build with a message
  naming both versions and saying the fix is a newer checker, because "failed to parse" would read
  as corruption and it is not.
- **Java 17 or later for the Gradle and Maven plugins.** Only the plugins: they are compiled at
  17 and so need the *build* to be running on 17 or newer, whatever release that build targets.
  Driving the checker directly — from the `core` jar, or with the agent — has no such floor.
- **Compiling with `--release 8`?** Still fine: every class in the `core` jar, annotations
  included, is Java 8 bytecode, so javac accepts the whole jar on the oldest supported compile
  classpath.
- **A build that produces `.class` files.** The checker analyses compiled bytecode, not source. It
  runs after compilation, never instead of it.

## Consuming the project today

Nothing is published to Maven Central or the Gradle Plugin Portal yet, and the build has no
`maven-publish` configuration, so the coordinates in the plugin snippets below describe intended
coordinates rather than something you can resolve. Until then, consume the project as a
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html):

```kotlin
// settings.gradle.kts in your project
includeBuild("../jvm-bytecode-performance-checks")
```

Or build and install locally:

```bash
git clone https://github.com/rob-langham/jvm-bytecode-performance-checks.git
cd jvm-bytecode-performance-checks
./gradlew build
```

That produces three things you might want:

| Artifact | Path | What it is |
| --- | --- | --- |
| Library jar | `core/build/libs/core-0.1.0.jar` | Annotations and the analyser API. A thin jar — ASM is a normal transitive dependency. |
| Agent jar | `core/build/libs/core-0.1.0-agent.jar` | The `-javaagent` flight recorder, with ASM shaded in. Self-contained. |
| Plugins | `gradle-plugin/`, `maven-plugin/` | Build integrations. |

## Step 1: depend on the annotations

The annotations live in `com.staticallocationchecker.annotations` in the `core` jar. They are
`RUNTIME`-retained, so they survive into the class file where the checker can see them.

```kotlin
dependencies {
    implementation("io.github.rob-langham:core:0.1.0")
}
```

{: .note }
> `RUNTIME` retention means the annotations are present in your shipped artifacts, and the `core`
> jar has to be on the runtime classpath for reflection over them to work. If you only ever run the
> checker at build time you can scope the dependency to `compileOnly`, at the cost of the runtime
> agent no longer being able to see the annotations.

## Step 2: annotate something

Start with one method that matters. Do not start by annotating a package.

```java
import com.staticallocationchecker.annotations.ZeroAllocations;

public final class OrderBook {

    @ZeroAllocations
    public long onTick(int slot, long tick) {
        ...
    }
}
```

Both annotations target `METHOD` and `TYPE`. On a type, the contract applies to every method the
class declares — including the constructor and the static initialiser, which is usually not what
you want on a first pass. See [annotation semantics](scenarios/annotation-semantics.md).

## Step 3: wire it into the build

### Gradle

```kotlin
plugins {
    id("io.github.rob-langham.static-allocation-checker")
}
```

This registers a `checkStaticAllocation` task in the `verification` group. It analyses the main
source set's classes and fails the build if there are any findings.

The task is **not** wired into `check` for you. To fail the normal build:

```kotlin
tasks.named("check") {
    dependsOn("checkStaticAllocation")
}
```

The task takes configuration:

```kotlin
tasks.named<StaticAllocationCheckerTask>("checkStaticAllocation") {
    // Extra analysis roots — other modules, or jars. Directories or .jar/.zip archives.
    classesDirs.from(project(":shared-domain").layout.buildDirectory.dir("classes/java/main"))

    // Roots used only to resolve callees, never scanned for annotated entry points.
    // Accepted and passed through; the analyser does not use it to widen resolution yet.
    resolveClasspath.from(configurations.runtimeClasspath)

    // Report findings without failing — for adopting this on an existing codebase.
    ignoreFailures.set(true)

    // Optional: write findings to a file, which also makes the task cacheable.
    reportFile.set(layout.buildDirectory.file("reports/static-allocation.txt"))
}
```

The task declares its inputs and outputs, so Gradle skips it when nothing has changed.

### Maven

```xml
<plugin>
  <groupId>io.github.rob-langham</groupId>
  <artifactId>maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

The `check` goal binds to the `verify` phase by default and analyses
`${project.build.outputDirectory}`. It is `threadSafe`, so parallel builds are fine.

It takes parameters:

```xml
<configuration>
  <!-- Extra analysis roots: other modules, or jars. -->
  <additionalRoots>
    <additionalRoot>${project.basedir}/../shared-domain/target/classes</additionalRoot>
  </additionalRoots>

  <!-- Resolution-only roots: followed when a hot path reaches them, never scanned for
       annotated entry points of their own. -->
  <resolveClasspath>
    <resolveClasspath>${project.basedir}/../shared-domain/target/classes</resolveClasspath>
  </resolveClasspath>

  <!-- Report without failing, for adoption on an existing codebase. -->
  <ignoreFailures>false</ignoreFailures>

  <skip>false</skip>
</configuration>
```

`skip` and `ignoreFailures` are also settable from the command line, as
`-Dstatic-allocation-checker.skip` and `-Dstatic-allocation-checker.ignoreFailures`.

### Neither: call it directly

The analyser is a plain library, which is the escape hatch when you want to drive it yourself:

```java
Report report = new AllocationChecker().analyze(
        List.of(Path.of("build/classes/java/main"),
                Path.of("build/libs/shared-domain.jar")),   // directories or jar/zip archives
        List.of(Path.of("libs/third-party.jar")));         // resolved through, not scanned

report.findings().forEach(System.out::println);
if (!report.isClean()) {
    throw new IllegalStateException(report.findings().size() + " findings");
}
```

`analyze` takes **analysis roots**: directories or `.jar`/`.zip` archives of class files. Annotated
entry points are discovered by scanning them, and callees are resolved within them.

The second parameter is the **resolve classpath**: classes that are followed when a hot path reaches
them, but never scanned for annotated entry points. That distinction is the point of having two
parameters — a dependency's code should be walked to find the allocation, while its own contracts
stay somebody else's business.

### Starting somewhere specific

A third parameter names the methods to start from, instead of discovering them by annotation:

```java
Report report = new AllocationChecker().analyze(
        List.of(Path.of("build/classes/java/main")),
        List.of(),
        List.of("com.example.OrderBook#onTick",          // every overload
                "com.example.Pricing#quote(JD)D",        // one exact overload
                "com.example.MatchingEngine"));          // every method it declares
```

Annotations are the right way to state a contract in code you own. Naming the starting point covers
the cases where they are not available or not appropriate: generated code, a dependency you cannot
edit, or simply asking *what does this one method allocate?* without committing the answer to a
source file.

When entry points are named, discovery by annotation is **skipped entirely** — the point of naming a
starting point is to analyse that, not that plus whatever else happens to be annotated nearby.
Warmup methods still act as boundaries wherever the walk reaches one.

Naming a class means every method it declares, **construction included**. That is rarely the hot
path, so name methods when you mean methods.

{: .warning }
> An entry point that matches nothing is an error, not an empty result. A typo in a class name would
> otherwise produce a clean report for code that was never looked at — which looks exactly like
> success.

{: .warning }
> Giving the checker too few roots does not make it quieter — it makes it noisier. Every call it
> cannot resolve becomes an [`UNANALYZABLE_CALL`](scenarios/unanalyzable-calls.md) finding. Point it
> at the modules your hot path actually calls into, and expect JDK calls to stay unresolved
> regardless.

## Step 4 (optional): the runtime flight recorder

Static analysis proves your warmup allocations *can* only happen on a guarded path. To see whether
they actually stop happening, attach the agent:

```bash
./gradlew :core:shadowJar

java -javaagent:core/build/libs/core-0.1.0-agent.jar -jar your-app.jar
```

The agent instruments `@AllocationsForWarmup` methods at class-load time and registers an MXBean
under `com.staticallocationchecker:type=AllocationFlightRecorder`, exposing:

| Attribute | Meaning |
| --- | --- |
| `TotalAllocations` | Total allocations recorded across all warmup sites |
| `Sites` | Per-site `count`, `firstSeenMillis`, `lastSeenMillis` |
| `reset()` | Clears all recorded sites |

Connect with JConsole, VisualVM, or any JMX client. To read it from inside the process, call
`AllocationFlightRecorder.instance().snapshot()`.

{: .note }
> Use the **agent** jar, not the library jar. A `-javaagent` jar is appended to the system class
> path with nothing beside it, so it has to be self-contained. The library jar deliberately declares
> no agent entry points, because it cannot honour them.

The full walkthrough, including what the numbers look like under sustained load, is in
[Warmup under load](runtime/steady-state.md).

## Verifying the setup

The fastest way to prove the checker is actually running is to make it fail on purpose:

```java
@ZeroAllocations
public Object canary() {
    return new Object();
}
```

You should see a warning line and a failed build:

```
static-allocation-checker: Finding[kind=ZERO_ALLOCATION_VIOLATION, className=com.example.OrderBook,
  methodName=canary, methodDescriptor=()Ljava/lang/Object;, line=42, category=NEW,
  callPath=[com.example.OrderBook#canary()Ljava/lang/Object;]]
```

If the build passes, the checker is not seeing your classes. Check that the task actually ran, that
it ran *after* compilation, and that the classes directory it analyses is the one your code compiles
into.

## Building the docs site

These pages are a Jekyll site under `docs/`, published to GitHub Pages by
`.github/workflows/docs.yml` on every push to `main`. To preview locally:

```bash
cd docs
bundle install
bundle exec jekyll serve
```

Pages is served from the workflow, so enable it once under **Settings → Pages** with **Source** set
to **GitHub Actions**.
