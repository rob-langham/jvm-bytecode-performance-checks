---
title: Recursion
parent: Allocation Scenarios
nav_order: 10
---

# Recursion

**A method that calls itself is reported once, not once per level — and the analysis terminates.**

This page is not about a kind of allocation. It is about what the checker does when the call graph
has a cycle in it, which is worth knowing so the output does not surprise you.

## The shape

```java
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
```

At runtime this allocates once, after four calls. A naive walker following every call would descend
into `recurse` forever, since it cannot know `n` reaches zero.

## What you get

One finding:

| Field | Value |
| --- | --- |
| `className` | `com.staticallocationchecker.fixtures.RecursiveAllocation` |
| `methodName` | `recurse` |
| `line` | `15` |
| `category` | `NEW` |
| `callPath` | `RecursiveAllocation#entry()` → `RecursiveAllocation#recurse(int)` |

Note the call path shows `recurse` **once**, not unrolled four deep. The path recorded is how the
walk first reached the method, not a simulation of the recursion.

## Why one finding is the right answer

The checker tracks which methods it has already walked, per annotated entry point. Reaching
`recurse` a second time stops immediately, before any instruction is examined.

Two things follow:

**It terminates.** The set of visited methods only grows and is bounded by the number of methods in
your code, so no cycle can loop forever. This covers mutual recursion — `a` calls `b` calls `a` —
as well as direct self-recursion.

**Each allocation site is reported once.** The question being answered is "can this path allocate",
and one finding answers it. Reporting the same `new` four times because the runtime might reach it
four times would be noise, not information — and the real answer is "an unbounded number of times",
which no static analysis can enumerate.

## Reading the call path

Because the walk is depth-first, `callPath` is the route of **first discovery**, not necessarily the
shortest route or the one you would have guessed. If a method is reachable several ways from the
same entry point, you get the first one the walk happened to take.

That is enough to locate the violation. Do not read it as "the only way this is reached" — there may
be others.

## Recursion on a hot path

The checker is silent about the recursion itself, which is worth remembering: a deeply recursive
method on a latency-sensitive path has costs it will never report — stack growth, no tail-call
elimination in HotSpot, and a `StackOverflowError` that is
[an exempt allocation](exceptions.md) when it happens.

Zero allocations is not the same as fast.
