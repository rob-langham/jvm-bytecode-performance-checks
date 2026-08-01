---
title: Lambdas
parent: Allocation Scenarios
nav_order: 5
---

# Lambdas

A lambda may or may not allocate, and the difference is visible in one place: whether the
`invokedynamic` has arguments.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/Lambdas.java`

```java
public class Lambdas {

    private static final Runnable NOOP = () -> {
    };

    @ZeroAllocations
    public Runnable stateless() {
        // Captures nothing: the JVM links this to a cached singleton, no per-call allocation.
        return () -> NOOP.run();
    }

    @ZeroAllocations
    public Runnable capturing(StringBuilder sink) {
        // Captures sink: a new instance is allocated on every evaluation.
        return () -> sink.append('x');
    }
}
```

## The bytecode

```
  public java.lang.Runnable stateless();
    Code:
       0: invokedynamic #7,  0              // InvokeDynamic #0:run:()Ljava/lang/Runnable;
       5: areturn

  public java.lang.Runnable capturing(java.lang.StringBuilder);
    Code:
       0: aload_1
       1: invokedynamic #11,  0             // InvokeDynamic #1:run:(Ljava/lang/StringBuilder;)Ljava/lang/Runnable;
       6: areturn
```

The whole difference is in the indy descriptor:

| Method | Descriptor | Meaning |
| --- | --- | --- |
| `stateless` | `()Ljava/lang/Runnable;` | **No** captured values |
| `capturing` | `(Ljava/lang/StringBuilder;)Ljava/lang/Runnable;` | One captured value: `sink` |

The argument types of an `invokedynamic` bootstrapped by `LambdaMetafactory` **are** the captured
values. A non-capturing lambda has nothing to hold, so `LambdaMetafactory` links the call site to a
single instance created once at link time and returns it forever. A capturing lambda must hold its
captures, so it constructs an instance per evaluation.

The bodies themselves compile to synthetic static methods:

```
  private static void lambda$capturing$2(java.lang.StringBuilder);
  private static void lambda$stateless$1();
```

## What the checker reports

**One** finding. `stateless()` is clean.

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.Lambdas` |
| `methodName` / `methodDescriptor` | `capturing` `(Ljava/lang/StringBuilder;)Ljava/lang/Runnable;` |
| `line` | `20` |
| `category` | `LAMBDA` |

## Why

```java
if (LAMBDA_METAFACTORY.equals(bootstrapOwner)) {
    // The indy descriptor's argument types are the captured values. A non-capturing
    // lambda (no arguments) links to a cached singleton and does not allocate.
    boolean capturing = Type.getArgumentTypes(indy.desc).length > 0;
    return capturing ? AllocationCategory.LAMBDA : null;
}
```

This is one of the few places where the checker can prove *absence* of allocation rather than
conservatively assuming it — the JLS and `LambdaMetafactory`'s specification both guarantee that a
non-capturing lambda need not produce a fresh instance, and HotSpot's implementation caches.

## What counts as capturing

| Expression | Captures | Allocates |
| --- | --- | --- |
| `() -> doSomething()` (static call) | nothing | No |
| `() -> CONSTANT.run()` (static field read) | nothing | No |
| `String::isEmpty` (unbound method reference) | nothing | No |
| `() -> this.field++` | `this` | **Yes** |
| `x -> x + local` | `local` | **Yes** |
| `someString::isEmpty` (bound method reference) | the receiver | **Yes** |

An **instance** method reference like `someString::isEmpty` captures its receiver and therefore
allocates. The unbound form `String::isEmpty` does not. They look almost identical in source.

Note that a lambda inside an instance method that touches any instance state captures `this`, which
is easy to do by accident:

```java
@ZeroAllocations
public void process() {
    items.forEach(item -> handler.handle(item));   // captures this (for handler) — allocates
}
```

## The gap: lambda bodies are not instrumented at runtime

A lambda body compiles to a synthetic method (`lambda$capturing$2` above) which carries **no
annotation** — annotations are not propagated to the desugared method. The static checker still
walks it if it is reachable through a normal call, but the [runtime agent](../runtime/steady-state.md)
only instruments methods carrying `@AllocationsForWarmup`, so a warmup method that does its work
inside a lambda records nothing. This is a known gap with a `@Disabled` test naming it.

## Fixing it

**Hoist the lambda into a field**, so it is allocated once:

```java
private final Runnable task = () -> sink.append('x');   // allocated in the constructor

@ZeroAllocations
public Runnable capturing() {
    return task;
}
```

**Or restructure so nothing is captured** — pass the state as a parameter of the functional
interface rather than closing over it:

```java
// Captures nothing; the sink arrives as an argument.
private static final Consumer<StringBuilder> APPEND = sink -> sink.append('x');
```
