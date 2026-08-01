---
title: Annotation semantics
parent: Allocation Scenarios
nav_order: 15
---

# Annotation semantics

Where the annotations can go, what they reach, and the three places the behaviour will surprise you.

All examples are from
`core/src/test/java/com/staticallocationchecker/fixtures/AnnotationSemantics.java` and
`TypeLevelZeroAllocations.java`.

## Both annotations are `@Target({METHOD, CONSTRUCTOR, TYPE})`

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface ZeroAllocations {
}
```

`RUNTIME` retention is what makes them visible in the class file — the checker reads
`visibleAnnotations`, which only carries runtime-retained annotations.

`ElementType` distinguishes `CONSTRUCTOR` from `METHOD`, so covering a constructor needs its own
target. Both annotations have one.

## Constructors can be annotated directly

`core/src/test/java/com/staticallocationchecker/fixtures/AnnotatedConstructors.java`

```java
public static class ZeroAllocationConstructor {
    private final Object value;

    @ZeroAllocations
    public ZeroAllocationConstructor(Object supplied) {
        this.value = supplied;
    }

    @ZeroAllocations
    public ZeroAllocationConstructor(int count) {
        this.value = new int[count];
    }

    /** Unannotated, so its allocation is nobody's business. */
    public ZeroAllocationConstructor() {
        this.value = new Object();
    }
}
```

One finding:

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `…AnnotatedConstructors$ZeroAllocationConstructor` |
| `methodName` / `methodDescriptor` | `<init>` `(I)V` |
| `line` | `20` |
| `category` | `NEW_ARRAY` |

Three constructors, and the checker picks out exactly the annotated one that allocates.
**Overloads are distinguished by descriptor**, so `<init>(Ljava/lang/Object;)V` is checked and
clean, `<init>(I)V` is checked and reported, and `<init>()V` is not an entry point at all — its
`new Object()` is unannotated and therefore nobody's business.

This is the reason it matters: covering a hot-path constructor no longer means annotating the whole
type and dragging in every other method with it.

The warmup contract applies to a constructor the same way:

```java
public static class WarmupConstructor {
    private Object cache;

    @AllocationsForWarmup
    public WarmupConstructor(boolean eager) {
        if (eager) {
            cache = new Object();       // guarded and cached: compliant
        }
    }
}

public static class NonCompliantWarmupConstructor {
    private Object cache;

    @AllocationsForWarmup
    public NonCompliantWarmupConstructor() {
        cache = new Object();           // WARMUP_NOT_GUARDED, line 55
    }
}
```

Entry-point discovery already iterated `<init>` — a constructor has always been just another method
in the class file. Only the `@Target` stood in the way.

{: .note }
> A warmup-annotated constructor is an odd thing to reach for. `@AllocationsForWarmup` describes
> lazy initialisation, and a constructor is eager by definition, so a compliant one has to be
> conditional on a parameter. The contract still applies uniformly, which is what the fixture pins
> down.

## Type-level `@ZeroAllocations` covers every method

```java
@ZeroAllocations
public class TypeLevelZeroAllocations {

    public Object first() {
        return new Object();
    }

    public Object second() {
        return new Object();
    }
}
```

Two findings, one per method.

```java
boolean typeLevel = hasAnnotation(classNode.visibleAnnotations, ZERO_ALLOCATIONS);
for (MethodNode method : classNode.methods) {
    if (isWarmup(classNode, method)) {
        analyzeWarmupMethod(classNode, method, index, findings);
    } else if (typeLevel || hasAnnotation(method.visibleAnnotations, ZERO_ALLOCATIONS)) {
        walkEntry(classNode, method, index, hierarchy, findings);
    }
}
```

`classNode.methods` is *every* method in the class file — which includes some you did not write.

## …including the constructor and the static initialiser

```java
@ZeroAllocations
public static class TypeLevelReachesInitialisers {
    static final Object STATIC_FIELD = new Object();

    private final Object field;

    public TypeLevelReachesInitialisers() {
        this.field = new Object();
    }
}
```

| `methodName` | `line` | `category` |
| --- | --- | --- |
| `<init>` | 26 | `NEW` |
| `<clinit>` | 21 | `NEW` |

Both are methods in the class file, so both are entry points. Field initialisers are compiled into
them, which is why `STATIC_FIELD = new Object()` — a line that looks nothing like a method body —
turns up as a finding in `<clinit>` at line 21.

{: .warning }
> **This is the main reason not to start with a type-level annotation.** Almost every class
> allocates in its constructor; that is what constructors are for. Type-level `@ZeroAllocations` is
> for a stateless helper class or a fully-preallocated hot-path object, not as a shortcut for
> annotating three methods.

## Type-level `@AllocationsForWarmup` applies the warmup contract to every method

```java
@AllocationsForWarmup
public static class TypeLevelWarmup {
    private Object cache;

    public Object compliant() {
        if (cache == null) {
            cache = new Object();
        }
        return cache;
    }

    public Object unconditional() {
        cache = new Object();
        return cache;
    }
}
```

One finding: `WARMUP_NOT_GUARDED` on `unconditional`, line 47. `isWarmup` checks the method and its
owner:

```java
private static boolean isWarmup(ClassNode owner, MethodNode method) {
    return hasAnnotation(method.visibleAnnotations, ALLOCATIONS_FOR_WARMUP)
            || hasAnnotation(owner.visibleAnnotations, ALLOCATIONS_FOR_WARMUP);
}
```

This is a reasonable thing to put on a dedicated initialisation class — every method gets held to
the lazy-init shape, and every method becomes a boundary that `@ZeroAllocations` walks stop at.

## Static and private methods work normally

```java
public static class MemberKinds {
    @ZeroAllocations
    public static Object staticMethod() {
        return new Object();
    }

    @ZeroAllocations
    private Object privateMethod() {
        return new Object();
    }
}
```

Both are reported (lines 56 and 61). Entry-point discovery is a scan over class files, not a
reachability analysis from some `main`, so accessibility is irrelevant — a `private` method with the
annotation is an entry point whether or not anything calls it.

## An override does not inherit the contract

```java
public static class AnnotatedParent {
    @ZeroAllocations
    public Object make() {
        return null;
    }
}

public static class UnannotatedOverride extends AnnotatedParent {
    @Override
    public Object make() {
        return new Object();
    }
}
```

**No finding.** `UnannotatedOverride#make` allocates, overrides a method under a zero-allocation
contract, and is reported nowhere.

This follows Java's own rule — annotations are not inherited by overriding methods, and
`@Inherited` only applies to class-level annotations on superclasses, never to methods. The class
file for `UnannotatedOverride` simply has no annotation on `make`, and there is nothing for the
scan to find.

It is still a hole. A `@ZeroAllocations` method on an interface or base class reads like a contract
on the API, and it is not one.

{: .warning }
> **Repeat the annotation on every override.** There is no check that will tell you that you forgot.
> Note the asymmetry with [virtual dispatch](virtual-dispatch.md): if some *other* annotated method
> calls `make()` through a `AnnotatedParent`-typed reference, the override **is** walked and the
> allocation **is** found. It is only the annotation-as-entry-point that fails to propagate.

## Both annotations on one method: warmup wins

```java
public static class BothOnOneMethod {
    @ZeroAllocations
    @AllocationsForWarmup
    public Object entry() {
        return new Object();
    }
}
```

One finding: `WARMUP_NOT_GUARDED` on line 14.

The `if (isWarmup(...))` branch is tested first, so the warmup contract applies and the
zero-allocation annotation is ignored entirely — silently. Two contradictory contracts on one method
should be an error; today it is a coin-flip decided by branch order. Known gap, with a `@Disabled`
test naming it.

## Summary

| Placement | Reaches |
| --- | --- |
| `@ZeroAllocations` on a method | That method and everything it calls transitively |
| `@ZeroAllocations` on a type | Every method **including `<init>` and `<clinit>`** |
| `@AllocationsForWarmup` on a method | That method's own allocations; also a boundary for walks |
| `@AllocationsForWarmup` on a type | Every method, same contract, all boundaries |
| Either, on a constructor | That constructor, selected by descriptor — other overloads are unaffected |
| Either, on an override of an annotated method | **Nothing** — annotations are not inherited |
| Both, on one method | Warmup wins, silently |
