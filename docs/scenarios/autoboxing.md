---
title: Autoboxing
parent: Allocation Scenarios
nav_order: 3
---

# Autoboxing

**Every time a primitive becomes an object, that is an allocation — and Java does it silently.**

This is the one that surprises people most, because there is nothing in the source to see. No
`new`, no method call you wrote, often no visible type at all.

## Why it allocates

`int` is not an object. It is 4 bytes of value, living in a register or on the stack.

`Integer` *is* an object: a heap-allocated box with a header, containing an `int`. So the moment a
primitive has to be treated as an object — stored in a collection, passed to a generic method,
assigned to an `Object` — the JVM has to put it in a box, and that box is an allocation.

```java
int n = 42;          // no object. Free.
Integer boxed = n;   // an object now exists. Allocated.
```

Java inserts that conversion for you, wherever the types require it. The assignment above is the
entire allocation.

## Where it happens

Almost always: **a primitive meeting a generic API.**

```java
@ZeroAllocations
public Level lookup(long instrumentId) {
    return levels.get(instrumentId);        // Map<Long, Level>: boxes on every single call
}

@ZeroAllocations
public void record(int size) {
    sizes.add(size);                        // List<Integer>: boxes
    counters.merge(key, 1L, Long::sum);     // boxes the 1L, and boxes the result
    log.info("size {}", size);              // boxes, because the parameter is Object
}
```

Generics are the root cause: `List<int>` is not expressible in Java, so every primitive that enters
a collection gets boxed on the way in. A `Map<Long, ?>` lookup on a hot path is a per-call
allocation that looks exactly like a free hash lookup.

Others worth knowing:

- **Mixed ternaries.** `flag ? 1 : someInteger` boxes the `1` to make the types match.
- **`String.format("%d", count)`** boxes `count` *and* allocates [a varargs array](varargs.md).
- **A primitive returned as `Object`**, or into a `Optional<Integer>`, or through a
  `Function<Integer, ?>`.

## The cache, and why it does not save you

`Integer.valueOf` keeps a cache of boxes for values in **-128 to 127** and returns those without
allocating. `Boolean`, `Byte`, and small `Character` and `Short` values are cached too.

So some boxing genuinely is free — but only for small values, and only by luck. An ID, a timestamp,
a price, a size in bytes: all outside the cache, all allocating. The checker reports boxing
regardless, because whether a particular call allocates depends on a runtime value it cannot see,
and code that boxes small numbers today is one refactor away from boxing large ones.

## Fixing it

**Use a primitive-specialised collection.** [Eclipse Collections](https://www.eclipse.org/collections/),
[fastutil](https://fastutil.di.unimi.it/), [Agrona](https://github.com/real-logic/agrona) and HPPC
all provide maps and lists that store primitives directly:

```java
// Before: allocates a Long on every lookup
private final Map<Long, Level> levels = new HashMap<>();

// After: no boxing, nothing to report
private final Long2ObjectOpenHashMap<Level> levels = new Long2ObjectOpenHashMap<>(1024);
```

**Or keep the primitive all the way through.** Where the key is dense and bounded, an index into a
preallocated array beats any map, and cannot box by construction.

**Do not try to outsmart the cache.** There is no suppression mechanism, so "this only ever boxes
small ints" is not something you can tell the checker. If you want it quiet, do not box.

## What the checker reports

From `core/src/test/java/com/staticallocationchecker/fixtures/Autoboxing.java`:

```java
@ZeroAllocations
public Object box(int n) {
    Integer boxed = n;
    return boxed;
}
```

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.Autoboxing` |
| `methodName` | `box` |
| `line` | `10` |
| `category` | `BOXING` |

## In the bytecode

{: .note }
> Optional.

The assignment compiles to a call you never wrote. Locals are numbered slots: `0` is `this`, `1` is
the parameter `n`, `2` is the local variable `boxed`.

```
  public java.lang.Object box(int);            stack after     locals
       0: iload_1                              [42]            0=this 1=42
                                               ^ a raw int, 4 bytes of value, no object
       1: invokestatic Integer.valueOf(I)      [Integer]       0=this 1=42
                                               ^ pops the int, pushes a REFERENCE to a
                                                 heap object that contains it
       4: astore_2                             []              0=this 1=42 2=Integer
       5: aload_2                              [Integer]
       6: areturn                              []
```

Instruction 0 has a number. Instruction 1 has a pointer to an object holding that number. The
allocation is the whole difference between those two lines, and in the source it is the `=`.

The checker matches that exact shape — a static call to a wrapper type's `valueOf` taking a single
primitive argument. The arity and primitive-argument tests are what keep `Integer.valueOf(String)`,
which is parsing rather than boxing, from being reported.
