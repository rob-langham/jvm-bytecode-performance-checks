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

- **Java 17 or later.** The build uses a Java 17 toolchain; the analyser reads class files up to
  whatever version the bundled ASM 9.7.1 supports.
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
| Library jar | `core/build/libs/core-0.1.0-SNAPSHOT.jar` | Annotations and the analyser API. A thin jar — ASM is a normal transitive dependency. |
| Agent jar | `core/build/libs/core-0.1.0-SNAPSHOT-agent.jar` | The `-javaagent` flight recorder, with ASM shaded in. Self-contained. |
| Plugins | `gradle-plugin/`, `maven-plugin/` | Build integrations. |

## Step 1: depend on the annotations

The annotations live in `com.staticallocationchecker.annotations` in the `core` jar. They are
`RUNTIME`-retained, so they survive into the class file where the checker can see them.

```kotlin
dependencies {
    implementation("com.staticallocationchecker:core:0.1.0-SNAPSHOT")
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
    id("com.staticallocationchecker.static-allocation-checker")
}
```

This registers a `checkStaticAllocation` task in the `verification` group. It analyses
`build/classes/java/main` and throws a `GradleException` if there are any findings.

The task is **not** wired into `check` for you. To fail the normal build:

```kotlin
tasks.named("check") {
    dependsOn("checkStaticAllocation")
}
```

### Maven

```xml
<plugin>
  <groupId>com.staticallocationchecker</groupId>
  <artifactId>maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

The `check` goal binds to the `verify` phase by default and analyses
`${project.build.outputDirectory}`. It is `threadSafe`, so parallel builds are fine.

### Neither: call it directly

The analyser is a plain library, which is the escape hatch when you need roots the plugins do not
give you:

```java
Report report = new AllocationChecker().analyze(
        List.of(Path.of("build/classes/java/main"),
                Path.of("build/libs/shared-domain.jar")),   // directories or jar/zip archives
        List.of());                                        // resolveClasspath: accepted, not yet used

report.findings().forEach(System.out::println);
if (!report.isClean()) {
    throw new IllegalStateException(report.findings().size() + " findings");
}
```

`analyze` takes **analysis roots**: directories or `.jar`/`.zip` archives of class files. Annotated
entry points are discovered by scanning them, and callees are resolved within them. The second
parameter is accepted but not yet used to widen resolution.

{: .warning }
> Giving the checker too few roots does not make it quieter — it makes it noisier. Every call it
> cannot resolve becomes an [`UNANALYZABLE_CALL`](scenarios/unanalyzable-calls.md) finding. The
> build plugins pass exactly one root each, which is the single biggest limitation to be aware of
> before you turn this on.

## Step 4 (optional): the runtime flight recorder

Static analysis proves your warmup allocations *can* only happen on a guarded path. To see whether
they actually stop happening, attach the agent:

```bash
./gradlew :core:shadowJar

java -javaagent:core/build/libs/core-0.1.0-SNAPSHOT-agent.jar -jar your-app.jar
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
