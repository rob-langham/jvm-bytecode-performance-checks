---
title: The warmup contract
parent: Allocation Scenarios
nav_order: 13
---

# The warmup contract

Real zero-allocation code still has to allocate its buffers and caches once. `@AllocationsForWarmup`
marks the place where that is allowed — under a contract, so it does not become a hole you can push
anything through.

Each allocation in an annotated method must be:

1. **guarded** — control-dependent on a branch, so some path through the method skips it; **and**
2. **cached** — the allocated reference flows into an instance or static field.

That is the shape of lazy initialisation, and nothing else.

## The three outcomes

`core/src/test/java/com/staticallocationchecker/fixtures/WarmupContract.java`

```java
public class WarmupContract {

    private Object cached;
    private Object other;

    @AllocationsForWarmup
    public Object compliant() {
        // Guarded by a branch and cached into a field: compliant.
        if (cached == null) {
            cached = new Object();
        }
        return cached;
    }

    @AllocationsForWarmup
    public Object unconditional() {
        // Cached, but allocated on every path (not guarded).
        other = new Object();
        return other;
    }

    @AllocationsForWarmup
    public Object discarded(boolean create) {
        // Guarded, but the allocation is not cached into a field.
        if (create) {
            return new Object();
        }
        return null;
    }
}
```

Findings:

| `methodName` | `kind` | `line` |
| --- | --- | --- |
| `compliant` | *(none)* | — |
| `unconditional` | `WARMUP_NOT_GUARDED` | 23 |
| `discarded` | `WARMUP_NOT_CACHED` | 31 |

---

## Compliant

```
  public java.lang.Object compliant();
    Code:
       0: aload_0
       1: getfield      #7                  // Field cached:Ljava/lang/Object;
       4: ifnonnull     18          <-- the guard: a path that skips the allocation
       7: aload_0
       8: new           #2                  // class java/lang/Object
      11: dup
      12: invokespecial #1                  // Method java/lang/Object."<init>":()V
      15: putfield      #7                  // Field cached:Ljava/lang/Object;   <-- the cache
      18: aload_0
      19: getfield      #7                  // Field cached:Ljava/lang/Object;
      22: areturn
```

Both halves are visible in the bytecode. `ifnonnull 18` creates a control-flow edge that jumps over
the `new`; `putfield` at offset 15 consumes the reference the `new` produced.

## `WARMUP_NOT_GUARDED`

```
  public java.lang.Object unconditional();
    Code:
       0: aload_0
       1: new           #2                  // class java/lang/Object
       4: dup
       5: invokespecial #1                  // Method java/lang/Object."<init>":()V
       8: putfield      #13                 // Field other:Ljava/lang/Object;
      11: aload_0
      12: getfield      #13                 // Field other:Ljava/lang/Object;
      15: areturn
```

The reference is cached — `putfield` is right there. But there is no branch: every execution of the
method reaches offset 1. There is no such thing as "warming up" here, because it happens every
time.

## `WARMUP_NOT_CACHED`

```
  public java.lang.Object discarded(boolean);
    Code:
       0: iload_1
       1: ifeq          12          <-- guarded
       4: new           #2                  // class java/lang/Object
       7: dup
       8: invokespecial #1                  // Method java/lang/Object."<init>":()V
      11: areturn                   <-- returned, never stored
      12: aconst_null
      13: areturn
```

Guarded, but the reference leaves the method without being retained. Nothing was warmed up: the next
call will do it again. A method like this is an allocating factory, not a warmup boundary.

---

## How guardedness is decided

Not by looking for `if` statements — by asking a reachability question on the control-flow graph:

> **an allocation is guarded if some method exit is reachable from entry without passing through it.**

```java
private static boolean exitReachableAvoiding(int avoid, Map<Integer, List<Integer>> successors,
                                             Set<Integer> exits) {
    if (avoid == 0) {
        return false;
    }
    Deque<Integer> queue = new ArrayDeque<>();
    Set<Integer> seen = new HashSet<>();
    queue.add(0);
    seen.add(0);
    while (!queue.isEmpty()) {
        int node = queue.poll();
        if (exits.contains(node)) {
            return true;
        }
        for (int next : successors.getOrDefault(node, List.of())) {
            if (next != avoid && seen.add(next)) {
                queue.add(next);
            }
        }
    }
    return false;
}
```

A breadth-first search from instruction 0 that is forbidden from entering the allocation, looking
for any `*RETURN` or `ATHROW`. The graph is built by overriding `Analyzer.newControlFlowEdge`, so
the edges are exactly the ones ASM's own dataflow analysis follows — including exception edges.

Defining it this way rather than pattern-matching on `if` means the contract is satisfied by any
control structure that can skip the allocation: `if`, a loop that may run zero times, a ternary, an
early return, a `switch`. See [warmup caching](warmup-caching.md) for those in bytecode.

## How caching is decided

An ASM `SourceInterpreter` dataflow analysis, with one modification:

```java
SourceInterpreter originPreserving = new SourceInterpreter(Opcodes.ASM9) {
    @Override
    public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
        return value;
    }
};
```

By default `SourceInterpreter.copyOperation` reports the *copying* instruction (the `DUP`, the
`ASTORE`) as the source of the value. Overriding it to return the incoming value unchanged means the
`SourceValue` reaching a `putfield` still names the original `new` — even if the reference went
through a `DUP` and a local-variable round-trip on the way. Without this, the extremely common

```java
Object created = new Object();
this.field = created;
```

would report `WARMUP_NOT_CACHED`, because the value at the `putfield` would trace back to the
`ALOAD`, not the `NEW`.

The set of "retained" producers is collected in `collectRetained`, which recognises four shapes —
direct field store, array element store, and passing to a method on a field-held receiver. Those are
covered in [warmup caching](warmup-caching.md).

## The boundary

`core/src/test/java/com/staticallocationchecker/fixtures/WarmupBoundary.java`

```java
public class WarmupBoundary {

    private Object cache;

    @ZeroAllocations
    public Object hot() {
        return warmup();
    }

    @AllocationsForWarmup
    private Object warmup() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }
}
```

**No findings.** `hot()` is under a zero-allocation contract and transitively reaches a `new`, but
the walk stops at the boundary:

```java
for (ClassHierarchy.MethodRef target : targets) {
    if (isWarmup(target.owner(), target.method())) {
        continue; // warmup boundary: its allocations are sanctioned, stop descending
    }
    …
```

`warmup()` is still checked, independently, as a warmup method — it just is not walked *as part of*
`hot()`. Both contracts are verified; neither leaks into the other.

This is the pattern to reach for whenever a hot path legitimately needs lazily-created state. It is
also the pattern to be suspicious of in review, because `@AllocationsForWarmup` on a large method
sanctions everything compliant inside it. Keep these methods small.

## What this cannot tell you

The contract is a *shape* check. It proves the allocation is on a guarded path that caches — not
that the guard eventually stops being taken.

```java
@AllocationsForWarmup
byte[] scratch(int size) {
    if (scratch == null || scratch.length < size) {   // guarded
        scratch = new byte[size];                      // cached
    }
    return scratch;
}
```

Perfectly compliant, and it reallocates forever under a workload whose sizes keep growing. Only a
running process can tell you which you have: see [Warmup under load](../runtime/steady-state.md).

## The gap: both annotations on one method

```java
@ZeroAllocations
@AllocationsForWarmup
public Object entry() {
    return new Object();
}
```

Warmup silently wins — `analyze` tests `isWarmup` first — and the result is a single
`WARMUP_NOT_GUARDED` finding rather than any complaint about the contradiction. This is a known gap
with a `@Disabled` test naming it.
