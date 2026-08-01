---
title: Direct new
parent: Allocation Scenarios
nav_order: 1
---

# Direct `new`

The base case: a `new` expression in a method under a zero-allocation contract.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/DirectNew.java`

```java
public class DirectNew {

    @ZeroAllocations
    public Object make() {
        return new Object();
    }
}
```

## The bytecode

```
  public java.lang.Object make();
    Code:
       0: new           #2                  // class java/lang/Object
       3: dup
       4: invokespecial #1                  // Method java/lang/Object."<init>":()V
       7: areturn
    LineNumberTable:
      line 10: 0
```

A Java `new Foo(...)` expression is three instructions, not one:

| Offset | Instruction | Does |
| --- | --- | --- |
| 0 | `new` | **Allocates** the object, uninitialised, and pushes the reference |
| 3 | `dup` | Copies the reference, because `invokespecial` consumes one |
| 4 | `invokespecial <init>` | Runs the constructor on the copy |

The heap allocation is the `new` at offset 0. `invokespecial` merely initialises what is already
there — which is why the checker treats them differently.

## What the checker reports

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.DirectNew` |
| `methodName` / `methodDescriptor` | `make` `()Ljava/lang/Object;` |
| `line` | `10` |
| `category` | `NEW` |
| `callPath` | `[…DirectNew#make()Ljava/lang/Object;]` |

One finding, not two. The `LineNumberTable` maps offset 0 to source line 10, which is where the
finding points.

## Why

`Allocations.categoryOf` switches on the opcode:

```java
case Opcodes.NEW:
    return isThrowableType.test(((TypeInsnNode) insn).desc) ? null : AllocationCategory.NEW;
```

Two consequences worth knowing:

**The constructor call is not itself a finding.** In `AllocationChecker.walk`, a `MethodInsnNode`
named `<init>` is explicitly excluded from descent:

```java
if (category == null && insn instanceof MethodInsnNode call && !call.name.equals("<init>")) {
```

The comment in the source explains the reasoning: constructor calls are *construction*, already
represented by the paired `new`. Descending into them would report the same allocation twice and
attribute allocations inside `Object.<init>` to your hot path.

**The allocated type decides exemption.** `new` of a `Throwable` subtype returns `null` — no
finding. See [exceptions](exceptions.md).

## Fixing it

Hoist the object out of the hot path and behind a warmup boundary:

```java
private Object instance;

@AllocationsForWarmup
private Object instance() {
    if (instance == null) {
        instance = new Object();
    }
    return instance;
}

@ZeroAllocations
public Object make() {
    return instance();   // clean: the walk stops at the boundary
}
```

That is exactly the [`WarmupBoundary` fixture](warmup-contract.md#the-boundary), and it reports
nothing.
