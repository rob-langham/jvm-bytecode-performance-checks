---
title: Transitive calls
parent: Allocation Scenarios
nav_order: 7
---

# Transitive calls

The annotated method allocates nothing. Something it calls does. This is the reason the tool exists
— it is the case code review reliably misses, because the two halves are in different files edited
at different times.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/TransitiveCaller.java`

```java
public class TransitiveCaller {

    @ZeroAllocations
    public Object entry() {
        return helper();
    }

    private Object helper() {
        return new Object();
    }
}
```

`entry()` is clean by inspection. `helper()` is not annotated at all.

## The bytecode

```
  public java.lang.Object entry();
    Code:
       0: aload_0
       1: invokevirtual #7                  // Method helper:()Ljava/lang/Object;
       4: areturn

  private java.lang.Object helper();
    Code:
       0: new           #2                  // class java/lang/Object
       3: dup
       4: invokespecial #1                  // Method java/lang/Object."<init>":()V
       7: areturn
```

## What the checker reports

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.TransitiveCaller` |
| `methodName` / `methodDescriptor` | **`helper`** `()Ljava/lang/Object;` |
| `line` | `14` |
| `category` | `NEW` |
| `callPath` | `[…TransitiveCaller#entry()Ljava/lang/Object;, …TransitiveCaller#helper()Ljava/lang/Object;]` |

Two things to notice:

1. **The finding names `helper`, not `entry`.** `className`/`methodName`/`line` always point at the
   instruction that allocates. The annotated entry point is the *first element of `callPath`*.
2. **`callPath` is the whole route.** For a violation several frames down, this is the only thing
   that tells you which annotated contract was broken and how the code got there.

## Why

`AllocationChecker.walk` is a depth-first traversal. At each non-allocating call instruction it
resolves targets and recurses, threading an extended `callPath` down:

```java
for (ClassHierarchy.MethodRef target : targets) {
    if (isWarmup(target.owner(), target.method())) {
        continue;   // warmup boundary: stop descending
    }
    List<String> nextPath = new ArrayList<>(callPath);
    nextPath.add(signature(target.owner(), target.method()));
    walk(target.owner(), target.method(), nextPath, visited, index, hierarchy, findings);
}
```

The traversal stops at three things: an already-visited method (see [recursion](recursion.md)), an
[`@AllocationsForWarmup` boundary](warmup-contract.md#the-boundary), and a call it cannot resolve
(reported as [`UNANALYZABLE_CALL`](unanalyzable-calls.md)).

## One helper, two entry points

`core/src/test/java/com/staticallocationchecker/fixtures/SharedHelper.java`

```java
public class SharedHelper {

    private Object helper() {
        return new Object();
    }

    @ZeroAllocations
    public Object entryA() {
        return helper();
    }

    @ZeroAllocations
    public Object entryB() {
        return helper();
    }
}
```

This produces **two** findings for the **same** instruction:

| `methodName` | `line` | `callPath` |
| --- | --- | --- |
| `helper` | 9 | `[…#entryA()…, …#helper()…]` |
| `helper` | 9 | `[…#entryB()…, …#helper()…]` |

The `visited` set is created fresh per entry point (`walkEntry` passes `new HashSet<>()`), so each
annotated contract is verified independently. That is the right call: `entryA` and `entryB` are two
separate promises, and both are broken. It does mean a widely-shared helper can produce a finding
per caller, which is worth knowing before you annotate a dozen entry points at once.

## Fixing it

Either fix the helper, or put a [warmup boundary](warmup-contract.md) on it if the allocation is
genuinely one-time. What you cannot do is annotate `helper` with `@ZeroAllocations` and expect the
violation to go away — that just adds a second entry point reporting the same instruction.
