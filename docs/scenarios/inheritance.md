---
title: Inheritance
parent: Allocation Scenarios
nav_order: 9
---

# Inheritance

**The method that runs may not be declared on the type you called it on — and the contract you
declared may not be on the method that runs.**

Two separate things travel up and down a class hierarchy, and the checker follows both:

- **the code**, when a subclass inherits a method it does not redeclare;
- **the contract**, when a subclass overrides a method that was annotated in the supertype.

## Finding the code

```java
public static class AllocatingParent {
    Object inherited() {
        return new Object();
    }
}

/** Inherits inherited() without redeclaring it. */
public static class AllocatingChild extends AllocatingParent {
}

@ZeroAllocations
public Object callsInheritedAllocatingMethod() {
    return allocating.inherited();     // called on the child
}
```

`AllocatingChild.class` contains no `inherited` method at all — the compiled class is empty. A
checker that looked only at the type named at the call site would find nothing and report the path
as clean.

Instead the finding is attributed to the parent, where the code actually lives:

| Field | Value |
| --- | --- |
| `className` | `…Inheritance$AllocatingParent` |
| `methodName` | `inherited` |
| `line` | `11` |
| `category` | `NEW` |
| `callPath` | `Inheritance#callsInheritedAllocatingMethod()` → `AllocatingParent#inherited()` |

The lookup climbs the superclass chain, then searches interfaces — matching the JVM's own
resolution order, and catching `default` methods, which live on an interface rather than on the
superclass chain.

A companion fixture, `callsInheritedCleanMethod`, reports nothing: resolution succeeded, the
parent's body was walked, and it does not allocate. Silence here means "checked and clean", not
"not found" — the difference between the two is what
[unanalyzable calls](unanalyzable-calls.md) exist to make visible.

## Inheriting the contract

An override does **not** have to repeat the annotation. The contract is inherited:

```java
public static class AnnotatedParent {
    @ZeroAllocations
    public Object make() {
        return null;
    }
}

/** Overrides an annotated method without repeating the annotation. */
public static class UnannotatedOverride extends AnnotatedParent {
    @Override
    public Object make() {
        return new Object();     // reported
    }
}
```

| Field | Value |
| --- | --- |
| `className` | `…AnnotationSemantics$UnannotatedOverride` |
| `methodName` | `make` |
| `line` | `103` |
| `category` | `NEW` |

This deliberately departs from Java's own rule, under which annotations are never inherited by
overriding methods. Following that rule exactly would have made `@ZeroAllocations` on an API almost
worthless: a base class could declare a contract and every subclass could quietly break it.

**Interfaces work the same way**, which is where it matters most:

```java
public interface AnnotatedInterface {
    @ZeroAllocations
    Object handle();
}

public static class UnannotatedImplementation implements AnnotatedInterface {
    @Override
    public Object handle() {
        return new Object();     // reported, line 117
    }
}
```

So annotating an interface method is a real contract on every implementation the checker can see.
That is the natural place to declare "implementations of this must not allocate".

## An override can declare its own contract

A declaration always beats an inherited one, so a subclass can legitimately change the terms:

```java
public static class OverrideDeclaringItsOwnContract extends AnnotatedParent {
    private Object cache;

    @Override
    @AllocationsForWarmup           // this wins over the inherited @ZeroAllocations
    public Object make() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }
}
```

No finding: the override is judged against the warmup contract it declares, and satisfies it. The
inherited `@ZeroAllocations` does not also apply — an explicit choice is never overridden by an
inherited one.

{: .note }
> Note this is not the same as putting both annotations on **one** method, which is a contradiction
> and is reported as [`CONFLICTING_CONTRACTS`](annotation-semantics.md#both-annotations-on-one-method).
> Here the two contracts are on different methods in a hierarchy, and the more specific one wins.

## In practice

- **Declare the contract once, on the interface or base class.** It propagates.
- **Expect findings on classes you did not annotate.** The `className` in a finding is where the
  allocation is; the contract may have been declared several types above it.
- **Use an override's own annotation to narrow the terms deliberately**, not by accident.
