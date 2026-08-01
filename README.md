# JVM Bytecode Performance Checks

A static analyser for JVM bytecode that enforces allocation contracts on hot paths. Annotate a
method as zero-allocation, and the checker walks its bytecode — and everything it calls
transitively — failing the build on any heap allocation it finds.

Built on [ASM](https://asm.ow2.io/). Requires Java 17+.

## Why

On a latency-sensitive path, an allocation is a future GC pause. Code review catches the obvious
`new`; it does not reliably catch autoboxing in a `Map<Long, ?>` lookup, a `String` concatenation
in a log line, a varargs array at a call site, a capturing lambda evaluated per invocation, or an
allocation three frames down in a helper someone edited last week. Those are all visible in the
bytecode, so this checks them there.

## The two contracts

### `@ZeroAllocations`

Applies to a method, or to a type (meaning every method on it). The checker walks the annotated
method and every method it calls transitively, reporting each allocation site:

| Category | Example |
| --- | --- |
| `NEW` | `new Foo()` |
| `NEW_ARRAY` | `new int[8]` |
| `BOXING` | `Integer.valueOf(i)`, autoboxing into a collection |
| `STRING_CONCAT` | `"a" + b` |
| `VARARGS_ARRAY` | the implicit array at a varargs call site |
| `LAMBDA` | a capturing lambda or method reference, which allocates per evaluation |

Two things are deliberately exempt. Allocations of `Throwable` subtypes are allowed — the
exceptional path is not the hot path. So are allocations behind an `@AllocationsForWarmup`
boundary, described next.

### `@AllocationsForWarmup`

Real zero-allocation code still has to allocate its buffers and caches once. This annotation marks
a method where allocation is permitted, but only under a contract that each allocation is:

1. **guarded** — control-dependent on a branch, so some path through the method skips it; and
2. **cached** — the allocated reference flows into an instance or static field.

That is the lazy-init shape. An allocation in such a method that is unconditional, or whose result
is thrown away, is reported (`WARMUP_NOT_GUARDED` / `WARMUP_NOT_CACHED`). When a `@ZeroAllocations`
walk reaches an annotated method it stops descending and treats the compliant allocations as
sanctioned.

Guardedness is established from the control-flow graph: an allocation is guarded if some exit is
reachable from entry without passing through it. Caching is established with a source-level
dataflow analysis whose interpreter preserves the originating instruction across `DUP` and
local-variable round-trips, so a reference can be traced to its field store even when it hops
through a local.

## Findings

Every finding carries its kind, class, method, source line (where line numbers survive
compilation), allocation category, and the **call path** from the annotated entry point down to the
site — so a violation four frames deep tells you how it was reached.

```
ZERO_ALLOCATION_VIOLATION  BOXING  OrderBook#onTick(J)V:142
  OrderBook#onTick(J)V -> PriceLevels#lookup(J)LLevel; -> java.util.HashMap#get(...)
```

A fifth kind, `UNANALYZABLE_CALL`, is reported when a callee is not on the analysis roots and so
cannot be walked. This is a soft spot worth being explicit about: the checker indexes only the
class files you point it at. A call into a third-party jar is reported as unanalyzable rather than
silently assumed clean — but it is reported once per site, and a codebase with a wide dependency
surface will see a lot of them. Virtual dispatch is likewise resolved against the declared owner
only; an override in an unindexed subclass is not followed.

## Usage

### Gradle

```kotlin
plugins {
    id("com.staticallocationchecker.static-allocation-checker")
}
```

Adds a `checkStaticAllocation` task in the `verification` group, which analyses
`build/classes/java/main` and fails the build on any finding.

### Maven

```xml
<plugin>
  <groupId>com.staticallocationchecker</groupId>
  <artifactId>maven-plugin</artifactId>
  <executions>
    <execution><goals><goal>check</goal></goals></execution>
  </executions>
</plugin>
```

Binds to the `verify` phase by default.

### Directly

```java
Report report = new AllocationChecker().analyze(List.of(Path.of("build/classes/java/main")), List.of());
report.findings().forEach(System.out::println);
```

## Runtime flight recorder

Static analysis proves a warmup allocation *can* only happen on a guarded path; it cannot prove it
only happens once in practice. The core jar doubles as a Java agent for that:

```
java -javaagent:core.jar -jar your-app.jar
```

It instruments `@AllocationsForWarmup` methods at class-load time to count their allocation sites,
and exposes the counts over JMX via `AllocationFlightRecorderMXBean` — total allocations, and a
per-site record with count and first/last-seen timestamps. A warmup site whose count keeps climbing
in steady state is a bug the static checker cannot see.

## Building

```
./gradlew build
```

Modules: `core` (analyser, annotations, agent, recorder), `gradle-plugin`, `maven-plugin`.

## Status

Early — `0.1.0-SNAPSHOT`. Nothing is published to an artifact repository yet, and no
`maven-publish` configuration exists, so the Gradle and Maven snippets above describe the plugins'
intended coordinates rather than something you can resolve today; consume the project via a
composite build (`includeBuild`) for now. The `resolveClasspath` parameter on `analyze` is accepted
but not yet used to widen callee resolution.

## Licence

[Apache 2.0](LICENSE)
