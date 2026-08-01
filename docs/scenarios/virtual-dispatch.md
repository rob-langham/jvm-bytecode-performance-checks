---
title: Virtual dispatch
parent: Allocation Scenarios
nav_order: 8
---

# Virtual dispatch

A call site names a type. The code that runs may be in any subtype. Resolving only against the
named type would report "no allocation" for an interface whose every implementation allocates —
the most dangerous answer a checker can give.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/Dispatch.java`

```java
public class Dispatch {

    public interface Handler {
        Object handle();
    }

    public static class AllocatingHandler implements Handler {
        @Override
        public Object handle() {
            return new Object();
        }
    }

    public abstract static class Base {
        abstract Object make();
    }

    public static class Impl extends Base {
        @Override
        Object make() {
            return new Object();
        }
    }

    private final Handler handler = new AllocatingHandler();
    private final Base base = new Impl();

    @ZeroAllocations
    public Object throughInterface() {
        return handler.handle();
    }

    @ZeroAllocations
    public Object throughAbstractClass() {
        return base.make();
    }
}
```

Neither annotated method contains anything that allocates. Neither `Handler.handle` nor
`Base.make` has a body.

## The bytecode

```
  public java.lang.Object throughInterface();
    Code:
       0: aload_0
       1: getfield      #10                 // Field handler:Lcom/…/Dispatch$Handler;
       4: invokeinterface #23,  1           // InterfaceMethod com/…/Dispatch$Handler.handle:()Ljava/lang/Object;
       9: areturn

  public java.lang.Object throughAbstractClass();
    Code:
       0: aload_0
       1: getfield      #19                 // Field base:Lcom/…/Dispatch$Base;
       4: invokevirtual #29                 // Method com/…/Dispatch$Base.make:()Ljava/lang/Object;
       7: areturn
```

The call site says `Dispatch$Handler.handle`. The bytecode that runs is in `Dispatch$AllocatingHandler`:

```
public class com.staticallocationchecker.fixtures.Dispatch$AllocatingHandler implements …$Handler {
  public java.lang.Object handle();
    Code:
       0: new           #2                  // class java/lang/Object
       3: dup
       4: invokespecial #1                  // Method java/lang/Object."<init>":()V
       7: areturn
}
```

## What the checker reports

Two findings, both attributed to the implementations:

| `className` | `methodName` | `line` | `category` | `callPath` |
| --- | --- | --- | --- | --- |
| `…Dispatch$AllocatingHandler` | `handle` | 17 | `NEW` | `…Dispatch#throughInterface()…` → `…$AllocatingHandler#handle()…` |
| `…Dispatch$Impl` | `make` | 30 | `NEW` | `…Dispatch#throughAbstractClass()…` → `…$Impl#make()…` |

The `callPath` is what makes these actionable: it names the annotated method whose contract was
broken *and* the implementation that broke it.

## Why

`ClassHierarchy.resolve` returns **every** body that could execute at the call site:

```java
MethodRef declared = declaredMethod(owner, name, descriptor);
if (declared != null && hasBody(declared)) {
    targets.add(declared);
}

// INVOKESTATIC and INVOKESPECIAL (constructors, private methods, super calls) are not
// dispatched dynamically, so the declaration is the whole answer.
boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
if (virtual) {
    targets.addAll(overridesOf(owner, name, descriptor));
}
```

Three details drive the behaviour above:

**`hasBody` excludes abstract and native methods.** `Handler.handle` and `Base.make` are abstract,
so the declaration contributes no target. Without this, an abstract declaration would "resolve"
successfully to an empty body and the call site would look clean.

**`overridesOf` scans the whole index.** Every indexed class that is a subtype of the named owner
and declares a body for that name and descriptor becomes a target. This is a
*class-hierarchy-analysis* approximation: it does not attempt to work out which implementation the
field actually holds, so if three classes implement `Handler`, all three are walked and any of them
allocating is a finding.

**Set semantics.** Targets are collected into a `LinkedHashSet`, so a subtype that *inherits*
rather than overrides resolves to the same `MethodRef` as its parent and is walked once. See
[inheritance](inheritance.md).

## The trade-off

Class-hierarchy analysis is conservative in the direction that matters — it never misses a
reachable allocation — but it over-approximates. If your interface has one hot implementation and
five that are only used in tests, and all six are in the analysis roots, all six are checked.

The mirror-image problem is worse and is the reason the checker refuses to guess: when **no**
implementation is in the roots, `resolve` returns empty and the call is reported as
[`UNANALYZABLE_CALL`](unanalyzable-calls.md), which is what happens to `Dispatch`'s third method:

```java
@ZeroAllocations
public int throughUnindexedInterface(java.util.List<String> values) {
    return values.size();     // java.util.List is not in the analysis roots
}
```

| `kind` | `className` | `line` | `callPath` |
| --- | --- | --- | --- |
| `UNANALYZABLE_CALL` | `…fixtures.Dispatch` | 50 | `…Dispatch#throughUnindexedInterface(Ljava/util/List;)I` → `java.util.List#size()I` |

## Fixing it

**Narrow the type at the call site.** If the hot path only ever holds one implementation, declaring
the field as the concrete class turns `invokeinterface` into `invokevirtual` on a type with no
subtypes — and, more usefully, makes the intent explicit.

**Or make every implementation zero-allocation.** If the polymorphism is real, the contract has to
hold for all of it. That is what the checker is telling you.
