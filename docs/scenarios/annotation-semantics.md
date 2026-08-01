---
title: Where annotations can go
parent: Allocation Scenarios
nav_order: 15
---

# Where annotations can go

**On a method, a constructor, or a whole type.** What each choice covers is not always obvious, and
one of them covers far more than people expect.

## On a method

The normal case. Covers that method and everything it calls
[transitively](transitive-calls.md).

Static and private methods work exactly the same way. Entry points are found by scanning class
files, not by tracing reachability from `main`, so a `private` method with the annotation is checked
whether or not anything calls it.

## On a constructor

```java
public static class ZeroAllocationConstructor {
    private final Object value;

    @ZeroAllocations
    public ZeroAllocationConstructor(Object supplied) {
        this.value = supplied;              // clean
    }

    @ZeroAllocations
    public ZeroAllocationConstructor(int count) {
        this.value = new int[count];        // reported, line 20, NEW_ARRAY
    }

    public ZeroAllocationConstructor() {
        this.value = new Object();          // not annotated: not checked
    }
}
```

**Overloads are independent.** Each constructor is selected by its signature, so annotating one says
nothing about the others. That is the point of being able to annotate a constructor at all: you can
cover the one that runs on a hot path without dragging in the others.

The warmup contract applies to constructors too, judged the same way:

```java
@AllocationsForWarmup
public WarmupConstructor(boolean eager) {
    if (eager) {
        cache = new Object();       // guarded and cached: compliant
    }
}

@AllocationsForWarmup
public NonCompliantWarmupConstructor() {
    cache = new Object();           // WARMUP_NOT_GUARDED, line 55
}
```

{: .note }
> A warmup-annotated constructor is an odd thing to want. `@AllocationsForWarmup` describes lazy
> initialisation, and a constructor is eager by definition — so satisfying the contract means making
> it conditional on a parameter. The rules apply uniformly; whether you want them to here is another
> question.

## On a type

Covers **every method the class declares** — including the ones you did not write.

```java
@ZeroAllocations
public class TypeLevelZeroAllocations {
    public Object first()  { return new Object(); }    // reported, line 10
    public Object second() { return new Object(); }    // reported, line 14
}
```

That much is expected. This is the part that is not:

```java
@ZeroAllocations
public static class TypeLevelReachesInitialisers {
    static final Object STATIC_FIELD = new Object();   // reported in <clinit>, line 42

    private final Object field;

    public TypeLevelReachesInitialisers() {
        this.field = new Object();                     // reported in <init>, line 47
    }
}
```

| `methodName` | `line` | Where it came from |
| --- | --- | --- |
| `<init>` | 47 | the constructor |
| `<clinit>` | 42 | the **static field initialiser** |

Field initialisers are compiled into the constructor and the static initialiser, so a line that
looks nothing like a method body turns up as a finding inside one.

{: .warning }
> **This is why you should not start with a type-level annotation.** Nearly every class allocates in
> its constructor — that is what constructors are for. Type-level `@ZeroAllocations` suits a
> stateless helper or a fully-preallocated hot-path object; it is not a shortcut for annotating
> three methods.

Type-level `@AllocationsForWarmup` works the same way, applying the warmup contract to every method,
and making every one of them a [boundary](warmup-contract.md#the-boundary). On a class whose whole
job is initialisation, that is a reasonable thing to want.

## Both annotations on one method

```java
@ZeroAllocations
@AllocationsForWarmup
public Object entry() {
    return new Object();
}
```

| `kind` | `category` | `line` |
| --- | --- | --- |
| `CONFLICTING_CONTRACTS` | `null` | `-1` |

The two contracts contradict each other — one forbids allocation, the other permits it under
conditions — so this is reported as a mistake in the declaration rather than resolved by picking a
winner. Decide which one you meant.

Note this is different from the same two annotations appearing on *different* methods in a
hierarchy, where the more specific declaration legitimately wins. See
[inheritance](inheritance.md#an-override-can-declare-its-own-contract).

## Inherited by overrides

An override does not need to repeat the annotation — the contract propagates down, including from
interfaces. That has its own section on the [inheritance page](inheritance.md#inheriting-the-contract),
because it is the most useful placement rule in the tool: **annotate the interface, and every
implementation is held to it.**

## Summary

| Placement | Covers |
| --- | --- |
| Method | That method, plus everything it calls transitively |
| Constructor | That constructor only — overloads are independent |
| Type | Every declared method, **including `<init>` and `<clinit>`** |
| Interface method | Every implementation the checker can see |
| Superclass method | Every override, unless the override declares its own contract |
| Both annotations, one method | Nothing — reported as `CONFLICTING_CONTRACTS` |

Both annotations are `@Retention(RUNTIME)`, which is what makes them visible in the compiled class
file where the checker reads them, and `@Target({METHOD, CONSTRUCTOR, TYPE})`.
