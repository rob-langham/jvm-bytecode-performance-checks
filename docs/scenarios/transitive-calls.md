---
title: Transitive calls
parent: Allocation Scenarios
nav_order: 7
---

# Transitive calls

**The contract covers everything the method calls, not just the method.**

This is the case code review misses most reliably, because the two halves live in different files
and are edited months apart.

## The shape

```java
@ZeroAllocations
public Object entry() {
    return helper();       // clean by inspection
}

private Object helper() {
    return new Object();   // not annotated, not obviously related
}
```

`entry()` is three lines and allocates nothing. It is still a violation, because `helper()`
allocates and `entry()` promised that nothing on its path would.

The finding names **`helper`**, not `entry`:

| Field | Value |
| --- | --- |
| `className` | `com.staticallocationchecker.fixtures.TransitiveCaller` |
| `methodName` | `helper` |
| `line` | `14` |
| `category` | `NEW` |
| `callPath` | `TransitiveCaller#entry()` → `TransitiveCaller#helper()` |

That is the rule everywhere: **`className` and `line` point at the allocation; `callPath[0]` is the
annotated method whose contract was broken.** For a violation four frames down, the call path is
the only thing that tells you which promise was broken and how the code got there.

## Why this is the whole point

An annotation that only covered the method it sat on would be nearly worthless. Real hot paths
delegate — to helpers, to collaborators, to library code — and the allocation is almost never in
the method you annotated. It is in the utility someone added a parameter to last week.

The walk stops at three things:

- **a method it has already visited** — see [recursion](recursion.md);
- **an [`@AllocationsForWarmup` boundary](warmup-contract.md#the-boundary)**, whose allocations are
  sanctioned by design;
- **a call it cannot resolve**, which is reported rather than assumed clean — see
  [unanalyzable calls](unanalyzable-calls.md).

## One helper, two entry points

```java
private Object helper() {
    return new Object();
}

@ZeroAllocations public Object entryA() { return helper(); }
@ZeroAllocations public Object entryB() { return helper(); }
```

This produces **two** findings for the **same** line of code:

| `methodName` | `line` | `callPath` |
| --- | --- | --- |
| `helper` | 9 | `entryA()` → `helper()` |
| `helper` | 9 | `entryB()` → `helper()` |

Each annotated method is a separate promise, and both are broken, so both are reported. Fixing
`helper` once fixes both.

Worth knowing before you annotate a dozen entry points at once: a widely-shared helper produces a
finding per caller. The count of findings is not the count of problems.

## Fixing it

**Fix the helper**, if the allocation is avoidable — and both callers benefit.

**Or make it a warmup boundary**, if the allocation is genuinely one-time:

```java
@AllocationsForWarmup
private Object helper() {
    if (cached == null) {
        cached = new Object();
    }
    return cached;
}
```

Now `entryA` and `entryB` are both clean, and `helper` is checked separately against the warmup
contract.

**What does not work** is annotating `helper` with `@ZeroAllocations` to "cover" it. That adds a
third entry point reporting the same allocation, and now you have three findings.
