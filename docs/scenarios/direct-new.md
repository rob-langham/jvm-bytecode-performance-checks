---
title: Direct new
parent: Allocation Scenarios
nav_order: 1
---

# Direct `new`

**You wrote `new`, so an object is allocated.** No subtlety here — this is the base case, and the
only one you would reliably catch by reading the code.

It is worth a page anyway, because two things about how the checker handles it explain its
behaviour everywhere else.

## One `new` is one finding

```java
@ZeroAllocations
public Object make() {
    return new Object();
}
```

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.DirectNew` |
| `methodName` | `make` |
| `line` | `10` |
| `category` | `NEW` |

One finding, not two — even though `new Foo()` in Java is *two* separate steps underneath:
allocating the object, then running its constructor. The checker counts the allocation and ignores
the constructor call.

That is why **an allocation inside a constructor is not attributed to you**. If `new Level()`
allocates three arrays in its own constructor, you get one finding for the `new Level()` and
nothing for what happens inside. The constructor's contents are that class's business; if it is on
your hot path, annotate it there.

## Where the object comes from does not matter

A `new` is a `new` whether it is yours or a library's, and whether or not you assign it:

```java
@ZeroAllocations
public void handle(Order order) {
    new StringBuilder();        // reported, even though it is discarded
    var copy = new Order(order);  // reported
    process(new int[8]);        // reported (as NEW_ARRAY)
}
```

The one exception is exceptions: `new IllegalStateException(...)` is deliberately exempt, because
the exceptional path is not the hot path. See [exceptions](exceptions.md).

## Fixing it

Move the object out of the hot path and create it once. The tool has a dedicated way to say that:

```java
private Object instance;

@AllocationsForWarmup
private Object instance() {
    if (instance == null) {
        instance = new Object();   // allowed: guarded by a branch, cached into a field
    }
    return instance;
}

@ZeroAllocations
public Object make() {
    return instance();             // clean
}
```

That reports nothing at all. The allocation still exists — it just happens once, on a path the
checker can prove is skippable. See [the warmup contract](warmup-contract.md).

## In the bytecode

{: .note }
> Optional. Nothing above depends on this.

The JVM is a stack machine: instructions push and pop values rather than naming registers. Reading
the stack column is what makes the two-step nature of `new Foo()` visible.

```
  public java.lang.Object make();                        stack after
       0: new           #2   // class java/lang/Object   [obj]         allocate; fields all zero,
                                                                       constructor NOT yet run
       3: dup                                            [obj, obj]    second reference to the
                                                                       same object, not a copy of it
       4: invokespecial #1   // Object."<init>":()V      [obj]         pops one reference, runs
                                                                       the constructor on it
       7: areturn                                        []            returns the other reference
```

The `dup` is there because `invokespecial` **consumes** the reference it initialises. Without the
duplicate there would be nothing left to return. Both entries point at the same object — the stack
holds references, so duplicating one costs nothing.

So the object exists, allocated and zeroed, from instruction 0. The constructor at instruction 4
only fills it in. That is why the checker reports the `new` and explicitly skips `<init>` calls: the
source comment calls constructor calls "construction, already represented by the paired allocation
opcode".
