---
title: Inheritance
parent: Allocation Scenarios
nav_order: 9
---

# Inheritance

The other half of resolution: the method that runs is declared in a **supertype** of the type named
at the call site.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/Inheritance.java`

```java
public class Inheritance {

    public static class AllocatingParent {
        Object inherited() {
            return new Object();
        }
    }

    /** Inherits {@code inherited()} without redeclaring it. */
    public static class AllocatingChild extends AllocatingParent {
    }

    public static class CleanParent {
        int inheritedClean() {
            return 42;
        }
    }

    public static class CleanChild extends CleanParent {
    }

    private final AllocatingChild allocating = new AllocatingChild();
    private final CleanChild clean = new CleanChild();

    @ZeroAllocations
    public Object callsInheritedAllocatingMethod() {
        return allocating.inherited();
    }

    @ZeroAllocations
    public int callsInheritedCleanMethod() {
        return clean.inheritedClean();
    }
}
```

`AllocatingChild` is an empty class. `AllocatingChild.class` contains no `inherited` method at all.

## The bytecode

The call site names the child:

```
  public java.lang.Object callsInheritedAllocatingMethod();
    Code:
       0: aload_0
       1: getfield      // Field allocating:L…/Inheritance$AllocatingChild;
       4: invokevirtual // Method …/Inheritance$AllocatingChild.inherited:()Ljava/lang/Object;
       7: areturn
```

Looking for `inherited` in `AllocatingChild` finds nothing — a naive resolver would stop there and
report the path as clean.

## What the checker reports

One finding, attributed to the **parent**, where the bytecode actually lives:

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.Inheritance$AllocatingParent` |
| `methodName` / `methodDescriptor` | `inherited` `()Ljava/lang/Object;` |
| `line` | `11` |
| `category` | `NEW` |
| `callPath` | `…Inheritance#callsInheritedAllocatingMethod()…` → `…$AllocatingParent#inherited()…` |

`callsInheritedCleanMethod` reports nothing — resolution succeeded, the parent's body was walked,
and it allocates nothing.

## Why

`ClassHierarchy.declaredMethod` climbs the superclass chain before giving up:

```java
private MethodRef declaredMethod(String owner, String name, String descriptor) {
    ClassNode current = index.get(owner);
    while (current != null) {
        MethodNode declared = findDeclared(current, name, descriptor);
        if (declared != null) {
            return new MethodRef(current, declared);
        }
        current = current.superName == null ? null : index.get(current.superName);
    }
    // Default methods are declared on an interface, which is not on the superclass chain.
    return interfaceMethod(owner, name, descriptor, new LinkedHashSet<>());
}
```

Two paths, matching the JVM's own resolution order:

1. **Superclass chain.** Walk up `superName` until the method is found.
2. **Interfaces.** If no superclass declares it, search the interface graph — because a `default`
   method lives on an interface, which is not on the superclass chain. `interfaceMethod` recurses
   through each interface's own super-interfaces, and carries a `visited` set so a diamond does not
   loop.

The climb stops at the edge of the index. `AllocatingParent extends Object`, and `java/lang/Object`
is not in the analysis roots, so the loop ends there — which is fine, because the method was found
before then. When it is *not* found, the result is empty and the caller reports
[`UNANALYZABLE_CALL`](unanalyzable-calls.md).

## Inheriting and overriding at once

Resolution collects into a `LinkedHashSet<MethodRef>`, and `MethodRef.equals` compares
owner-name, method-name and descriptor. So for a call on `AllocatingChild`:

- `declaredMethod` climbs and yields `AllocatingParent#inherited`;
- `overridesOf` scans for indexed subtypes of `AllocatingChild` declaring a body — there are none.

One target, one walk, one finding. If `AllocatingChild` *had* overridden `inherited`, both bodies
would be targets and both would be walked, because either could execute depending on the runtime
type at the call site.

## The gap: annotations are not inherited

The converse case does **not** work, and this is a known gap:

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
        return new Object();     // reported? No.
    }
}
```

`UnannotatedOverride#make` produces no finding. Entry points are discovered by scanning for
annotations on the class file, and Java annotations are not inherited by overriding methods — so
the override silently drops the contract. See
[annotation semantics](annotation-semantics.md#an-override-does-not-inherit-the-contract).

Until that is closed, if a type's contract matters, **repeat the annotation on every override**.
