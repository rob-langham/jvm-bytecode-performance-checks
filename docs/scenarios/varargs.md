---
title: Varargs
parent: Allocation Scenarios
nav_order: 6
---

# Varargs

**Calling a `foo(int...)` method allocates an array, every time, at the call site.**

Varargs is a compiler convenience with a runtime cost. There is no such thing as a variable-length
argument list in the JVM — the method really takes an array, and somebody has to build it. That
somebody is the caller.

## Why the caller pays

```java
static int count(int... values) { ... }

count(1, 2, 3);
```

The method's real signature is `count(int[])`. So at every call site, the compiler writes the code
you did not:

```java
count(new int[] {1, 2, 3});   // what actually gets compiled
```

The array is allocated by *your* method, on *your* hot path, and thrown away as soon as the call
returns. Call it a million times and you allocate a million arrays.

The allocation belongs to the caller, which has a useful consequence: **you cannot fix this by
changing the callee.** Adding a fixed-arity overload is a fix; optimising the varargs method is not.

## Where it bites

Varargs is everywhere in APIs that look free:

| Call | Allocates |
| --- | --- |
| `log.info("a {} b {}", x, y)` | an `Object[]`, plus [boxing](autoboxing.md) if `x`/`y` are primitives |
| `String.format("%d/%d", a, b)` | an `Object[]`, plus boxing, plus the result `String` |
| `Objects.hash(a, b)` | an `Object[]` — **always**, even for two arguments |
| `Arrays.asList(a, b)` | an `Object[]` |
| `EnumSet.of(A, B, C, D, E, F)` | an `Object[]`, from six arguments up |
| `List.of(a, b, c)` | **nothing** — see below |

Two are worth calling out:

**`List.of` and `Set.of` have fixed-arity overloads** for up to ten elements. `List.of(a, b, c)`
calls `of(E, E, E)` and allocates no array. Only at eleven arguments does it reach the varargs
overload. (The list object itself is still an allocation.)

**`Objects.hash(a, b)` always allocates.** Its signature is `hash(Object...)` with no fixed-arity
overload, so the two-argument case builds an array. Writing the arithmetic out —
`31 * Objects.hashCode(a) + Objects.hashCode(b)` — does not.

## What does *not* allocate

Passing an array you already have. There is nothing to build:

```java
@ZeroAllocations
public int passesExistingArray(int[] existing) {
    return count(existing);      // clean: no array is synthesised
}
```

This is the escape hatch for a hot path that must call a varargs API: keep a preallocated array in
a field, fill it in place, and pass it.

## Fixing it

**Use a fixed-arity overload if one exists.** Most logging frameworks provide one- and
two-argument forms for exactly this reason — SLF4J's `info(String, Object, Object)` allocates no
array, though it will still box primitives.

**Write one yourself** for your own hot APIs:

```java
// Before
static int count(int... values);
count(1, 2, 3);                     // allocates

// After
static int count(int a, int b, int c);
count(1, 2, 3);                     // no array
```

**Or pass a reused array**, as above.

## What the checker reports

From `core/src/test/java/com/staticallocationchecker/fixtures/Varargs.java`:

| `methodName` | Source | `line` | `category` |
| --- | --- | --- | --- |
| `passesPrimitiveVarargs` | `count(1, 2, 3)` | 18 | **`VARARGS_ARRAY`** |
| `passesObjectVarargs` | `countObjects(a, b)` | 23 | **`VARARGS_ARRAY`** |
| `passesExplicitArrayToAnOrdinaryParameter` | `total(new int[] {1, 2, 3})` | 43 | `NEW_ARRAY` |
| `passesExistingArray` | `count(existing)` | — | *(no finding)* |

Varargs arrays get their own category, distinct from an array you wrote yourself. The third row is
what makes that distinction meaningful: `total(new int[] {1, 2, 3})` compiles to exactly the same
thing a varargs call does, and is still reported as an ordinary `NEW_ARRAY` — because `total` takes
a real array parameter. The checker tells them apart by looking at the method being called, not at
the call site.

So a `VARARGS_ARRAY` finding tells you something a `NEW_ARRAY` finding does not: **the array is not
in your source.** Look at the method being called, not the line.
