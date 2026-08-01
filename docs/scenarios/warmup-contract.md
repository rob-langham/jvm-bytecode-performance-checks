---
title: The warmup contract
parent: Allocation Scenarios
nav_order: 13
---

# The warmup contract

**Zero-allocation code still has to allocate its buffers once. `@AllocationsForWarmup` is where you
say so — and it holds you to what "once" means.**

## The problem it solves

Every allocation-free hot path has an allocation somewhere: the buffer it writes into, the cache it
reads from, the pool it borrows from. Those objects have to be created.

You could create them in the constructor, but often you cannot — the size is not known yet, the
resource is not available yet, or creating it eagerly would slow startup for a path that may never
run. So you create it lazily, on first use:

```java
private long[] levels;

long[] levels() {
    if (levels == null) {
        levels = new long[64];
    }
    return levels;
}
```

This allocates exactly once per instance, then never again. But it sits directly on the hot path,
and a checker walking `@ZeroAllocations` would report it, correctly, as a `new`.

`@AllocationsForWarmup` marks it as intentional:

```java
@AllocationsForWarmup
long[] levels() { ... }
```

Now the allocation is permitted — but only if it really is the shape above.

## The two conditions

An allocation in an annotated method is accepted only if it is:

**1. Guarded** — some path through the method skips it. In the example, when `levels` is already
set, the `if` is not entered and nothing is allocated. Without a guard there is no "first use": it
happens every time, and that is not warmup, it is just allocating.

**2. Cached** — the object is kept. It goes into a field, an array, or a collection held in a field.
Without this, the object is created and thrown away, so the next call creates another one. Again:
not warmup.

Together they say "this happens on a path that can be skipped, and after it happens the result is
kept" — which is exactly what makes it happen once.

## The three outcomes

From `core/src/test/java/com/staticallocationchecker/fixtures/WarmupContract.java`:

```java
@AllocationsForWarmup
public Object compliant() {
    if (cached == null) {        // guarded
        cached = new Object();   // cached
    }
    return cached;
}
```

No finding.

```java
@AllocationsForWarmup
public Object unconditional() {
    other = new Object();        // cached, but happens every single call
    return other;
}
```

| `kind` | `methodName` | `line` |
| --- | --- | --- |
| `WARMUP_NOT_GUARDED` | `unconditional` | 23 |

```java
@AllocationsForWarmup
public Object discarded(boolean create) {
    if (create) {
        return new Object();     // guarded, but nothing keeps it
    }
    return null;
}
```

| `kind` | `methodName` | `line` |
| --- | --- | --- |
| `WARMUP_NOT_CACHED` | `discarded` | 31 |

The third one is a factory, not a warmup method. It allocates fresh every time it is called, and
the annotation would have quietly blessed that if the contract did not exist.

## The boundary

The second thing the annotation does is stop the walk. A `@ZeroAllocations` method that calls a
warmup method is clean:

```java
@ZeroAllocations
public Object hot() {
    return warmup();          // no finding: the walk stops here
}

@AllocationsForWarmup
private Object warmup() {
    if (cache == null) {
        cache = new Object();
    }
    return cache;
}
```

**No findings at all.** `hot()` transitively reaches a `new`, and that is fine, because it reaches
it through a boundary whose allocations have been justified. `warmup()` is still checked — just
separately, against its own contract.

This is how you carve a legitimate initialisation island out of a hot path.

It is also, obviously, how you could hide an allocation you did not want to deal with. A large
method with this annotation sanctions everything compliant inside it. **Keep warmup methods small
enough to read in one go** — that is the only real defence, and it is worth saying in review.

## What it cannot tell you

The contract checks a *shape*. It proves the allocation is on a skippable path that keeps its
result. It does not prove the path stops being taken.

```java
@AllocationsForWarmup
byte[] scratch(int size) {
    if (scratch == null || scratch.length < size) {   // guarded
        scratch = new byte[size];                      // cached
    }
    return scratch;
}
```

Fully compliant. Zero findings. And under a workload whose requests keep getting bigger, it
reallocates forever.

Whether the guard eventually stops being taken depends on the *values* the method is called with,
which no bytecode analysis can know. For that there is the runtime recorder, and a worked example
of exactly this trap: [Warmup under load](../runtime/steady-state.md).
