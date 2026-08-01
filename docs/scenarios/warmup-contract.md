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

## In the bytecode

{: .note }
> Optional. This section is about how the two conditions are checked, not about a new kind of
> allocation.

```
  public java.lang.Object compliant();                     stack after     
       0: aload_0                                          [this]
       1: getfield cached:Ljava/lang/Object;               [cached]
       4: ifnonnull 18                                     []              <-- THE GUARD:
                                                                           jumps over everything
                                                                           below when already set
       7: aload_0                                          [this]
       8: new #2  // java/lang/Object                      [this, obj]     <-- THE ALLOCATION
      11: dup                                              [this, obj, obj]
      12: invokespecial Object."<init>"                    [this, obj]
      15: putfield cached:Ljava/lang/Object;               []              <-- THE CACHE:
                                                                           consumes the reference
                                                                           into the field
      18: aload_0                                          [this]
      19: getfield cached:Ljava/lang/Object;               [cached]
      22: areturn                                          []
```

**Guardedness** is decided by a reachability question, not by looking for `if` statements: *is some
`return` reachable from the start of the method without passing through this allocation?* Here,
`ifnonnull` jumps to 18, which reaches `areturn` at 22 having skipped instruction 8. That definition
means any control structure qualifies — `if`, a loop that may run zero times, a ternary, an early
return.

**Caching** is decided by following the value. The reference produced at instruction 8 is the one
consumed by `putfield` at 15, so the allocation is retained. The analysis follows references through
copies and local variables, so the extremely common

```java
Object created = new Object();
this.field = created;
```

is recognised too, even though the value reaching `putfield` came from a local rather than directly
from the `new`. The shapes that count as caching are on the [next page](warmup-caching.md).
