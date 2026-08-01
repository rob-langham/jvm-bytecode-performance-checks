---
title: Usage and Best Practices
nav_order: 3
---

# Usage and Best Practices

{: .no_toc }

1. TOC
{:toc}

---

## Reading a finding

A `Finding` has seven fields. Throughout these docs they are shown as a table:

| Field | Example | Notes |
| --- | --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` | One of five; see below |
| `className` | `com.example.PriceLevels` | Binary name of the class **containing the site** |
| `methodName` | `lookup` | The method containing the site, not the annotated entry point |
| `methodDescriptor` | `(J)Ljava/lang/Object;` | JVM descriptor, so overloads are distinguishable |
| `line` | `41` | Source line, or `-1` where line numbers did not survive compilation |
| `category` | `NEW` | `null` for `UNANALYZABLE_CALL` |
| `callPath` | `[…#onTick(J)V, …#lookup(J)…]` | Entry point → site |

The five kinds:

| Kind | Means |
| --- | --- |
| `ZERO_ALLOCATION_VIOLATION` | An allocation reachable from a `@ZeroAllocations` entry point |
| `WARMUP_NOT_GUARDED` | A warmup allocation on every path through the method |
| `WARMUP_NOT_CACHED` | A warmup allocation whose reference is not retained |
| `UNANALYZABLE_CALL` | A call that resolved to nothing in the analysis roots, or a warmup method whose dataflow analysis failed |
| `CONFLICTING_CONTRACTS` | One method claims both contracts at once — see [where annotations can go](scenarios/annotation-semantics.md#both-annotations-on-one-method) |

The six allocation categories are `NEW`, `NEW_ARRAY`, `BOXING`, `STRING_CONCAT`, `VARARGS_ARRAY`
and `LAMBDA`. `category` is `null` for the two kinds that are not about a specific allocation.

**`className` is where the allocation is, not where the annotation is.** This trips people up. If
`OrderBook#onTick` is annotated and the allocation is in `PriceLevels#lookup`, the finding names
`PriceLevels`. The annotated method is the *first* element of `callPath`.

## Where to put `@ZeroAllocations`

**On the narrowest method that is genuinely hot.** The checker walks transitively, so annotating an
entry point covers everything below it. Annotating a whole type covers the constructor and the
static initialiser too, which almost always allocate legitimately — see
[annotation semantics](scenarios/annotation-semantics.md).

```java
// Good: the contract is on the thing that runs per message.
public final class Session {
    @ZeroAllocations
    public void onMessage(ByteBuffer message) { ... }

    // Not annotated: runs once, at startup.
    public void configure(Config config) { ... }
}
```

**Not on a method whose whole job is construction.** A factory allocating is not a bug. If you find
yourself annotating something and then fighting the results, the annotation is in the wrong place.

**Not, at first, on anything that calls into a third-party library.** Every unresolvable call is a
finding. Start inside your own code and expand outwards.

## Where to put `@AllocationsForWarmup`

On the lazy-init method, and nowhere else. The contract it enforces is specifically the lazy-init
shape: **guarded** by a branch and **cached** into a field.

```java
private long[] levels;

@AllocationsForWarmup
long[] levels() {
    if (levels == null) {          // guarded: a path skips the allocation
        levels = new long[64];     // cached: the reference reaches a field
    }
    return levels;
}
```

It is a boundary as much as a contract: when a `@ZeroAllocations` walk reaches an annotated method
it **stops descending** and treats the compliant allocations as sanctioned. That makes it the tool
for carving a legitimate initialisation island out of a hot path — and equally the tool for hiding
an allocation you did not want to deal with, so keep these methods small enough to read.

The three outcomes, and the shapes that count as "cached", are in
[the warmup contract](scenarios/warmup-contract.md) and [what counts as cached](scenarios/warmup-caching.md).

## Best practices

### Turn it on for one path, not one codebase

The checker fails the build on *any* finding, and there is no baseline file or per-site suppression.
Turning it on across an existing codebase produces a wall of findings, most of them
`UNANALYZABLE_CALL` from JDK calls. Pick the hot path you actually care about and annotate that.

For the transition, both plugins can report without failing — `ignoreFailures` in Gradle,
`-Dstatic-allocation-checker.ignoreFailures=true` in Maven — which lets you watch the count come
down before you make it fatal.

### Prefer preallocated state to clever avoidance

Most violations have the same fix: hoist the allocation out of the hot path into a field, behind a
warmup boundary. Object pools, reusable buffers, preallocated exception instances, primitive
collections keyed on `long` rather than `Long`.

```java
// Before: allocates a Long key on every lookup.
private final Map<Long, Level> levels = new HashMap<>();

// After: no boxing, nothing to check.
private final LongObjectMap<Level> levels = new LongObjectMap<>(1024);
```

### Watch for the allocations with no `new`

In rough order of how often they surprise people:

1. [Autoboxing](scenarios/autoboxing.md) — any primitive reaching a generic API.
2. [String concatenation](scenarios/string-concat.md) — including in a log line that is disabled at runtime.
3. [Varargs](scenarios/varargs.md) — `String.format`, `List.of(a, b, c)`, most logging APIs.
4. [Capturing lambdas](scenarios/lambdas.md) — a lambda that closes over anything, evaluated per call.

### Keep exception allocation where it is

Allocations of `Throwable` subtypes are exempt: the exceptional path is not the hot path. You do not
need to preallocate exceptions to satisfy the checker, and doing so costs you the stack trace. See
[exceptions](scenarios/exceptions.md).

### Static analysis first, then the recorder

The static checker and the runtime agent answer different questions:

| Question | Tool |
| --- | --- |
| Can this path allocate at all? | Static checker |
| Is this warmup allocation on a guarded, caching path? | Static checker |
| Does this warmup site actually stop firing under load? | [Runtime recorder](runtime/steady-state.md) |
| How many times did it fire, and when was it last seen? | Runtime recorder |

The static checker is cheap and belongs in CI. The recorder needs a realistic workload and belongs
in your load-test environment.

## Dealing with unanalyzable calls

This will be most of your findings at first. A call is unanalyzable when nothing it could reach is
present in the analysis roots.

**Give it more roots.** Both plugins take them, so a multi-module build no longer needs the library
API:

```kotlin
// Gradle
tasks.named<StaticAllocationCheckerTask>("checkStaticAllocation") {
    classesDirs.from(project(":shared-domain").layout.buildDirectory.dir("classes/java/main"))
}
```

```xml
<!-- Maven -->
<configuration>
  <additionalRoots>
    <additionalRoot>${project.basedir}/../shared-domain/target/classes</additionalRoot>
  </additionalRoots>
</configuration>
```

Roots can be directories or `.jar`/`.zip` archives.

**Do not point it at the JDK.** Calls into `java.*` will always be unanalyzable in practice. The
realistic approach is to not have `java.*` calls on the annotated path — which, on a genuine
zero-allocation path, is roughly the position you want to be in anyway.

**Read it as "unverified", not "broken".** The finding means the checker declined to guess. That is
the correct default for a verification tool, but it does mean the noise floor is a function of how
much of your dependency surface you can point it at. See
[unanalyzable calls](scenarios/unanalyzable-calls.md).

## Interpreting a clean report

A clean report means: no allocation was found on any path the checker could see, from any annotated
entry point. It does not mean:

- **that the path never allocates** — an `UNANALYZABLE_CALL`-free run over roots that exclude your
  dependencies still says nothing about what those dependencies do;
- **that a warmup site fires only once** — that is a runtime property; see
  [Warmup under load](runtime/steady-state.md);
- **that the JIT will not allocate** — the checker sees bytecode. Scalar replacement can remove
  allocations the checker reports, and deoptimisation can reintroduce them.

## Known gaps

All eight gaps tracked after the first review round are now closed. What remains:

- **Nothing is published to an artifact repository.** See
  [Setup](setup.md#consuming-the-project-today).
- **There is no suppression mechanism.** No baseline file, no per-site ignore, no severity levels. A
  finding you have decided to accept has to be fixed or the check turned off for the module —
  `ignoreFailures` reports without failing, but reports everything.
