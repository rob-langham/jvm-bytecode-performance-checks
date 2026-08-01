---
title: Introduction
nav_order: 1
---

# Static Allocation Checker

A static analyser for JVM bytecode that enforces allocation contracts on hot paths. Annotate a
method as zero-allocation, and the checker walks its bytecode — and everything it calls
transitively — failing the build on any heap allocation it finds.

Built on [ASM](https://asm.ow2.io/). Requires Java 17+.

---

## The problem

On a latency-sensitive path, an allocation is a future GC pause. You can review code for `new` and
catch most of it. What review does not reliably catch is the allocation that has no `new` in the
source at all:

```java
@ZeroAllocations
public long onTick(long instrumentId, int size) {
    Level level = levels.get(instrumentId);   // autoboxes instrumentId into a Long
    log.debug("tick " + instrumentId);        // allocates a String, and the arguments array
    return level.accept(size);                // whose implementation allocates, three frames down
}
```

Three allocations, no `new`. Every one of them is plainly visible in the compiled bytecode —
`invokestatic Long.valueOf`, an `invokedynamic` to `StringConcatFactory`, an `anewarray` for the
varargs — so that is where this tool looks.

## What it does

Two annotations describe the contract, and the checker verifies it against the `.class` files your
build already produces.

`@ZeroAllocations` marks a method (or a whole type) as being on a hot path. The checker walks it
and every method it calls transitively, reporting each allocation site it reaches:

| Category | Source that produces it |
| --- | --- |
| [`NEW`](scenarios/direct-new.md) | `new Foo()` |
| [`NEW_ARRAY`](scenarios/arrays.md) | `new int[8]`, and the [implicit varargs array](scenarios/varargs.md) |
| [`BOXING`](scenarios/autoboxing.md) | `Integer.valueOf(i)`, autoboxing into a collection |
| [`STRING_CONCAT`](scenarios/string-concat.md) | `"a" + b` |
| [`LAMBDA`](scenarios/lambdas.md) | a *capturing* lambda or method reference |

`@AllocationsForWarmup` marks the place where allocation is allowed — the lazy-init of a buffer or
a cache — but only under a contract: each allocation must be **guarded** by a branch and **cached**
into a field. See [the warmup contract](scenarios/warmup-contract.md).

## What makes it different from reading the code

**It follows calls, not declarations.** A call site is walked through every implementation reachable
from it that is present in the analysis roots, so an allocation in an interface implementation or an
inherited method is found and attributed to the method that actually contains it. See
[virtual dispatch](scenarios/virtual-dispatch.md) and [inheritance](scenarios/inheritance.md).

**It reports the path, not just the site.** Every finding carries a `callPath` from the annotated
entry point down to the allocation, so a violation several frames deep tells you how it was reached:

```
static-allocation-checker: Finding[kind=ZERO_ALLOCATION_VIOLATION,
  className=com.staticallocationchecker.fixtures.TransitiveCaller, methodName=helper,
  methodDescriptor=()Ljava/lang/Object;, line=14, category=NEW,
  callPath=[com.staticallocationchecker.fixtures.TransitiveCaller#entry()Ljava/lang/Object;,
            com.staticallocationchecker.fixtures.TransitiveCaller#helper()Ljava/lang/Object;]]
```

That is the raw log line the build plugins emit today, wrapped here to fit. Throughout these docs
the same information is shown as a table, because it is easier to read; see
[Reading a finding](usage.md#reading-a-finding).

**It refuses to guess.** A call it cannot resolve to any bytecode in the analysis roots is reported
as [`UNANALYZABLE_CALL`](scenarios/unanalyzable-calls.md) rather than assumed clean. That is the
right default for a verification tool, and it is the setting you will spend the most time tuning —
see [Usage](usage.md#dealing-with-unanalyzable-calls).

## What static analysis cannot tell you

The checker proves a warmup allocation *can* only happen on a guarded path. It cannot prove it only
happens *once*. A buffer that is reallocated whenever a request outgrows it satisfies the contract
perfectly and still allocates forever under a workload whose requests keep growing.

For that there is a `-javaagent` flight recorder that counts warmup allocation sites at runtime and
exposes them over JMX. Put a real workload through it and a warmed-up site stops contributing while
a leaking one keeps climbing:

```
round    operations   recorded      new   sites
1           2000000         36       36   PricingEngine#levels=4  ResizingCache#scratch=32
...
8          16000000        204        4   PricingEngine#levels=4  ResizingCache#scratch=200
```

Both classes pass the static check. Only one of them actually reaches a steady state. That is
[the load-test walkthrough](runtime/steady-state.md), and its output above is real.

## Where to go next

- [Setup Guide](setup.md) — get it running against your build.
- [Usage and best practices](usage.md) — where to put the annotations, and how to live with the results.
- [Allocation scenarios](scenarios/) — one page per thing that allocates: the Java, the bytecode it
  compiles to, what the checker reports, and why.
- [Warmup under load](runtime/steady-state.md) — the runtime recorder, and proving steady state.

## Status

Early — `0.1.0-SNAPSHOT`, nothing published to an artifact repository yet. See
[Setup](setup.md#consuming-the-project-today) for how to consume it in the meantime, and
[Known gaps](usage.md#known-gaps) for what it does not do.
