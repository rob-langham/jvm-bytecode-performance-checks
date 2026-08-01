---
title: Virtual dispatch
parent: Allocation Scenarios
nav_order: 8
---

# Virtual dispatch

**When you call a method through an interface, the code that runs is in some implementation — so
that is where the checker looks.**

This is the difference between a tool that reads your source and one that reads your program.

## Why it needs saying

```java
private final Handler handler = new AllocatingHandler();

@ZeroAllocations
public Object throughInterface() {
    return handler.handle();
}
```

There is nothing here that allocates. `Handler.handle()` is an interface method — it has no body at
all. A checker that resolved the call to the declaration it names would find an empty method,
conclude "no allocations", and pass.

That is the worst possible answer, because it is a confident one. The allocation is in
`AllocatingHandler.handle()`, one virtual call away:

```java
public static class AllocatingHandler implements Handler {
    @Override
    public Object handle() {
        return new Object();
    }
}
```

So the checker resolves a call site to **every implementation it can see**, and walks all of them:

| `className` | `methodName` | `line` | `callPath` |
| --- | --- | --- | --- |
| `…Dispatch$AllocatingHandler` | `handle` | 17 | `Dispatch#throughInterface()` → `AllocatingHandler#handle()` |
| `…Dispatch$Impl` | `make` | 30 | `Dispatch#throughAbstractClass()` → `Impl#make()` |

Abstract classes behave identically — an `abstract` method has no body either.

## Every implementation, not the likely one

The checker does not try to work out which implementation your field actually holds. If three
classes implement `Handler` and all three are in the analysis roots, all three are checked, and any
one of them allocating is a finding.

That cuts both ways:

- **It cannot miss a reachable allocation**, which is the property you want from a verification tool.
- **It will check implementations you do not care about.** If your interface has one hot
  implementation and five used only in tests, and the tests are in the analysis roots, you will hear
  about all six.

Keeping test implementations out of the analysis roots is usually the answer — the build plugins
analyse main classes only by default.

## When it cannot see any implementation

If *no* implementation is in the analysis roots, there is nothing to walk, and the checker says so
rather than passing:

```java
@ZeroAllocations
public int throughUnindexedInterface(java.util.List<String> values) {
    return values.size();       // java.util.List: not in the analysis roots
}
```

| `kind` | `line` | `callPath` |
| --- | --- | --- |
| `UNANALYZABLE_CALL` | 50 | `Dispatch#throughUnindexedInterface(List)` → `java.util.List#size()` |

Note the trap this avoids. `List` has no body for `size()` — it is an interface. If the checker
treated "resolved to a declaration" as success, this would look identical to a verified-clean call.
Abstract and native methods are explicitly rejected as walk targets for exactly that reason. See
[unanalyzable calls](unanalyzable-calls.md).

## In practice

**Declare the contract on the interface.** An annotation there applies to every implementation the
checker can see — see [inheritance](inheritance.md#inheriting-the-contract). That is far more
useful than annotating one implementation and hoping.

**Narrowing the type at the call site narrows the check.** If the hot path only ever holds one
implementation, declaring the field as the concrete class means only that class is walked. It also
makes the intent explicit, which is worth more than the analysis benefit.

**A finding on an implementation you have never heard of is still real.** Read the `callPath`: it
names the annotated method whose contract was broken, and the route from it to the allocation.

## In the bytecode

{: .note }
> Optional — and there is deliberately little to see, which is the point.

```
  public java.lang.Object throughInterface();                    stack after
       0: aload_0                                                [this]
       1: getfield handler:LDispatch$Handler;                    [handler]
       4: invokeinterface Dispatch$Handler.handle:()Object;      [result]
       9: areturn                                                []
```

Nothing here allocates. The call site names `Dispatch$Handler.handle` — an interface method with no
code — and the JVM decides at runtime which body to run based on the object in `handler`. The
checker has to make the same decision statically, and does it by walking every candidate.
