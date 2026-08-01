---
title: Recursion
parent: Allocation Scenarios
nav_order: 10
---

# Recursion

A transitive walk over a call graph with cycles has to terminate, and it has to report each site
once rather than once per way of reaching it.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/RecursiveAllocation.java`

```java
public class RecursiveAllocation {

    @ZeroAllocations
    public Object entry() {
        return recurse(3);
    }

    private Object recurse(int n) {
        if (n <= 0) {
            return new Object();
        }
        return recurse(n - 1);
    }
}
```

## The bytecode

```
  public java.lang.Object entry();
    Code:
       0: aload_0
       1: iconst_3
       2: invokevirtual #7                  // Method recurse:(I)Ljava/lang/Object;
       5: areturn

  private java.lang.Object recurse(int);
    Code:
       0: iload_1
       1: ifgt          12
       4: new           #2                  // class java/lang/Object
       7: dup
       8: invokespecial #1                  // Method java/lang/Object."<init>":()V
      11: areturn
      12: aload_0
      13: iload_1
      14: iconst_1
      15: isub
      16: invokevirtual #7                  // Method recurse:(I)Ljava/lang/Object;   <-- back edge
      19: areturn
```

The instruction at offset 16 calls back into the method the walker is currently inside.

## What the checker reports

Exactly one finding:

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.RecursiveAllocation` |
| `methodName` / `methodDescriptor` | `recurse` `(I)Ljava/lang/Object;` |
| `line` | `15` |
| `category` | `NEW` |
| `callPath` | `…RecursiveAllocation#entry()…` → `…RecursiveAllocation#recurse(I)…` |

Note that `callPath` shows `recurse` once, not four times. The path recorded is the one by which the
walk **first** reached the method, not an unrolling of the recursion.

## Why

The first thing `walk` does is check a visited set:

```java
private void walk(ClassNode owner, MethodNode method, List<String> callPath,
                  Set<String> visited, …) {
    if (!visited.add(key(owner, method))) {
        return;
    }
    …
```

where `key` is `owner.name + "#" + method.name + method.desc`. Reaching `recurse` a second time
returns immediately, before any instruction is examined.

Two properties follow:

**Termination.** The set grows monotonically and is bounded by the number of methods in the index,
so the traversal cannot loop. This covers mutual recursion (`a` → `b` → `a`) as well as direct
self-recursion.

**One finding per site per entry point.** Because the guard fires before the instruction scan, the
allocation at offset 4 is examined once. It does not matter that the recursion could reach it four
times at runtime — the question the checker answers is "can this path allocate", and one finding
answers it.

The `visited` set is created fresh for each annotated entry point in `walkEntry`, which is why a
[shared helper reachable from two entry points](transitive-calls.md#one-helper-two-entry-points)
produces two findings: two separate contracts, verified independently.

## What this means for the call path

`callPath` is the route of first discovery in a depth-first traversal, not necessarily the shortest
route or the one you would guess. If a method is reachable by several paths from the same entry
point, you get the first one the walk took. That is enough to locate the violation, but do not read
it as "the only way this is reached".
