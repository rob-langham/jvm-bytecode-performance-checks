---
title: Arrays
parent: Allocation Scenarios
nav_order: 2
---

# Arrays

**Every `new int[n]` is an allocation, and its size does not have to be large to matter.**

Arrays are the allocation people most often think of as free, because they feel like a primitive
rather than an object. They are objects, with a header, and they come from the same heap.

## Why they cost

An array is allocated in one step — unlike [`new Foo()`](direct-new.md) there is no constructor to
run, because the JVM zeroes the memory for you. That zeroing is the hidden cost: allocating
`new byte[1 << 16]` also writes 64KB of zeros before you have stored anything.

A short-lived array is also the classic way to make the garbage collector's job worse, because it
is usually too big for the fast path and too short-lived to be worth promoting.

## The three flavours

All three report as `NEW_ARRAY`. The distinction rarely matters in practice — the checker treats
them identically — but it is worth knowing they are three different things underneath:

| Java | What it is |
| --- | --- |
| `new int[10]` | A one-dimensional array of a primitive |
| `new String[10]` | A one-dimensional array of a reference type |
| `new int[2][3]` | A multi-dimensional array |

`new int[2][3]` is a single finding even though it puts **three** objects on the heap — one outer
array and two inner ones. The checker reports allocation *sites*, not object counts. For the
question "does this path allocate", one finding answers it.

## Where they come from when you did not write one

This is the useful part. Most `NEW_ARRAY` findings have no `[` in the source line:

| Source | Allocates an array because |
| --- | --- |
| `count(1, 2, 3)` calling `count(int...)` | [varargs](varargs.md) — the compiler builds the array for you |
| `list.add(x)` past capacity | `ArrayList` grows by copying into a bigger array |
| `String.toCharArray()`, `getBytes()` | returns a fresh copy every call |
| `Arrays.copyOf`, `clone()`, `toArray()` | copying is the whole point |
| `EnumSet.of(A, B, C, D, E, F)` | the 6+ overload is varargs |

Most of these land in library code, so you will typically see them as
[unanalyzable calls](unanalyzable-calls.md) rather than `NEW_ARRAY` — the checker cannot see inside
`java.util`, so it flags the call rather than the array.

## Fixing it

**Preallocate and reuse.** This is the single most common shape in allocation-free code:

```java
private long[] buffer;

@AllocationsForWarmup
long[] buffer() {
    if (buffer == null) {
        buffer = new long[64];    // allowed: guarded and cached
    }
    return buffer;
}

@ZeroAllocations
public void handle(long tick) {
    long[] target = buffer();     // clean
    ...
}
```

**Size it for the worst case, not the common one.** A buffer that is reallocated whenever it is
outgrown still satisfies the checker, and still allocates forever in production. That trap has its
own walkthrough: [Warmup under load](../runtime/steady-state.md).

**Watch out for `clear()` versus reallocating.** `list.clear()` keeps the backing array;
`list = new ArrayList<>()` throws it away and allocates a new one on first use.

## What the checker reports

From `core/src/test/java/com/staticallocationchecker/fixtures/ArrayAllocations.java`:

| `methodName` | Source | `line` | `category` |
| --- | --- | --- | --- |
| `primitiveArray` | `new int[10]` | 10 | `NEW_ARRAY` |
| `referenceArray` | `new String[10]` | 15 | `NEW_ARRAY` |
| `multiArray` | `new int[2][3]` | 20 | `NEW_ARRAY` |

There is no exemption for arrays. Even an array of exceptions is reported — the
[`Throwable` exemption](exceptions.md) covers throwing, and an array is not something you throw.
