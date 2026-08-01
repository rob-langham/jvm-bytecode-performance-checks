# Demos

A runnable scenario per capability, each in its own folder with its own `build.gradle.kts`.

This is a **standalone Gradle build**. It is not listed in the root `settings.gradle.kts`, so
`./gradlew build` at the repo root never builds it, and nothing here can break the library.

## Run everything

From the repository root:

```bash
./gradlew -p demo demo
```

That compiles every scenario, runs the checker over each, and prints the findings side by side.
The scenarios that are *meant* to produce findings set `ignoreFailures`, so a red build here means
something is genuinely wrong.

Then the runtime one, which needs a real JVM with the agent attached:

```bash
./gradlew -p demo :07-runtime-flight-recorder:run
```

## The scenarios

| | Shows | Expected |
| --- | --- | --- |
| [01-zero-allocation-basics](01-zero-allocation-basics) | Every allocation category, in the shape it really appears | 6 findings |
| [02-clean-hot-path](02-clean-hot-path) | The same work written to allocate nothing | clean — and the build enforces it |
| [03-warmup-contract](03-warmup-contract) | Guarded-and-cached lazy init, and the two ways to get it wrong | 2 findings |
| [04-dispatch-and-inheritance](04-dispatch-and-inheritance) | Allocation behind an interface, an override, and an inherited method | 5 findings |
| [05-varargs](05-varargs) | The array a varargs call site synthesises for you | 2 findings |
| [06-conflicting-contracts](06-conflicting-contracts) | Both annotations on one declaration | 1 finding |
| [07-runtime-flight-recorder](07-runtime-flight-recorder) | Two classes the static checker cannot tell apart | clean statically; the difference is visible only at runtime |

Only **02** is configured to fail its build on a finding. That is what a real project would do; the
rest set `ignoreFailures` so they can show you their output instead of stopping the run.

## How the Gradle setup works

Each scenario's build file is the whole story — three lines of it:

```kotlin
plugins {
    java
    id("io.github.rob-langham.static-allocation-checker")
}

dependencies {
    implementation("io.github.rob-langham:core:0.1.0")  // the annotations
}
```

Applying the plugin registers `checkStaticAllocation`, wires it into `check`, and defaults it to
the main source set's output. So `./gradlew build` runs it with no further configuration.

Optional configuration, all of it demonstrated somewhere here:

```kotlin
tasks.checkStaticAllocation {
    classesDirs.setFrom(sourceSets["main"].output.classesDirs)  // the default
    resolveClasspath.setFrom(configurations.runtimeClasspath)   // see the note below
    ignoreFailures.set(true)                                    // warn instead of fail
    reportFile.set(layout.buildDirectory.file("reports/allocations.txt"))
}
```

### Where the artifacts come from

Nothing is published to a repository yet, so `demo/settings.gradle.kts` pulls both the plugin and
the annotations out of the sibling build:

```kotlin
pluginManagement {
    includeBuild("..")   // makes the plugin id resolvable
}
includeBuild("..")       // substitutes io.github.rob-langham:core
```

Both lines are needed — they are separate mechanisms, and `pluginManagement` alone resolves the
plugin but not the dependency. **In a real project you would delete both** and let the coordinates
resolve from a repository as normal; nothing else in these build files would change.

## Two things the output will show you

**`UNANALYZABLE_CALL` on JDK calls.** Scenario 01 reports one for `Map.get`, because `java.util`
is not among the analysis roots. The checker flags what it could not follow rather than assuming it
is clean. Widening `resolveClasspath` is intended to reduce these — it is accepted by the checker
but not yet used, so today the honest answer is that you will see them.

**The same finding more than once.** Scenario 04 reports `BoxingHandler.handle` twice: once reached
through `dispatch()`, and once as an entry point in its own right, because it inherits
`@ZeroAllocations` from the interface. Each carries the call path that reached it — one allocation,
two ways in.

## Related

[`examples/steady-state-demo`](../examples/steady-state-demo) covers the same runtime idea as
scenario 07, driven by `javac` and raw `java` rather than Gradle, if you want to see it without a
build tool in the way.
