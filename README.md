# JVM Bytecode Performance Checks

A static analyser for JVM bytecode that enforces allocation contracts on hot paths. Annotate a
method as zero-allocation, and the checker walks its bytecode — and everything it calls
transitively — failing the build on any heap allocation it finds.

Built on [ASM](https://asm.ow2.io/). Runs on Java 17+ and analyses bytecode compiled for
Java 8, 11, 17, 21 or 25 — the annotations themselves are compiled for Java 8, so even a
`--release 8` build can declare the contracts. Apache 2.0.

### 📖 **[Read the documentation →](https://rob-langham.github.io/jvm-bytecode-performance-checks/)**

Everything below is a summary. The docs carry the detail: setup, the contracts, and a worked page
per allocation category.

---

## The problem

```java
@ZeroAllocations
public long onTick(long instrumentId, int size) {
    Level level = levels.get(instrumentId);   // autoboxes instrumentId into a Long
    log.debug("tick " + instrumentId);        // allocates a String, and the arguments array
    return level.accept(size);                // whose implementation allocates, three frames down
}
```

Three allocations, and not a `new` in sight. Review catches the obvious one; it does not reliably
catch these. All three are unmistakable in the compiled class, because the compiler had to write
out the boxing, the string building and the argument array explicitly — so that is where this tool
looks.

Two annotations describe the contract. `@ZeroAllocations` marks a hot path and forbids allocation
anywhere it can reach. `@AllocationsForWarmup` marks the lazy-init that *is* allowed to allocate,
and checks it is genuinely guarded and cached.

For what it catches, how it follows calls through interfaces and inherited methods, and the runtime
flight recorder that finds what static analysis structurally cannot —
[read the docs](https://rob-langham.github.io/jvm-bytecode-performance-checks/).

## Getting started

[Setup](https://rob-langham.github.io/jvm-bytecode-performance-checks/setup/) has the Gradle and
Maven configuration; [Usage](https://rob-langham.github.io/jvm-bytecode-performance-checks/usage/)
covers reading the findings.

Or run the demos, which need nothing published:

```bash
./gradlew -p demo demo                                  # every scenario, findings side by side
./gradlew -p demo :07-runtime-flight-recorder:run       # the runtime recorder, under load
```

## What's in this repository

| | |
| --- | --- |
| [`core/`](core) | The analyser, the annotations, the java agent and the flight recorder |
| [`gradle-plugin/`](gradle-plugin) | `checkStaticAllocation`, wired into `check` |
| [`maven-plugin/`](maven-plugin) | The `check` goal, bound to `verify` |
| [`demo/`](demo) | A runnable scenario per capability — a standalone build, not in this one's reactor |
| [`docs/`](docs) | The documentation site |
| [`examples/`](examples) | A javac-driven runtime demo, no build tool involved |

## Building

```bash
./gradlew build         # library and plugins, ~170 tests
./gradlew -p demo demo  # the demos, which assert their own output
```

`./gradlew build` includes `:core:agentTest`, which launches real JVMs with `-javaagent` and
attaches to a running one. CI runs everything on JDK 17, 21 and 25; any JDK works locally, since
the pinned Java 17 toolchain downloads itself if missing.

## Status

Early — `0.1.0`. Publishing is configured for Maven Central and the Gradle Plugin Portal under
`io.github.rob-langham`, but **nothing has been released yet**; see [PUBLISHING.md](PUBLISHING.md)
for the remaining account setup. Until then, consume the project via a composite build, as
[`demo/`](demo) does.

Known gaps are tracked as
[issues](https://github.com/rob-langham/jvm-bytecode-performance-checks/issues).

## Licence

[Apache 2.0](LICENSE)
