---
title: Lambdas
parent: Allocation Scenarios
nav_order: 5
---

# Lambdas

**A lambda allocates if it captures something. If it captures nothing, it is free.**

That one sentence is the whole rule. The rest of this page is why it is true, and how to tell which
kind you are looking at — which is harder than it sounds, because the two look almost identical in
source.

## Why capturing costs an allocation

A lambda has to become an object, because the thing receiving it expects an object — a `Runnable`,
a `Comparator`, whatever the interface is.

If the lambda captures nothing, every evaluation of it would produce an identical, stateless
object. So the JVM makes **one** the first time the line runs, and hands you the same one forever
after. Nothing is allocated on subsequent calls.

If the lambda captures a variable, the object has to *hold* that variable — it is not stateless any
more. Two evaluations that capture different values need two different objects. So the JVM has no
choice but to build a fresh one **every time the lambda is evaluated**.

```java
// Captures nothing. One object exists for the life of the program.
Runnable a = () -> System.out.println("tick");

// Captures `count`. A new object every time this line runs.
Runnable b = () -> System.out.println("tick " + count);
```

The cost is not in *running* the lambda. It is in *creating* it — so a capturing lambda in a method
called a million times a second allocates a million objects a second, even if the lambda body
itself does nothing.

## What counts as capturing

This is where it gets slippery. Capturing is not about how much code is in the lambda; it is about
whether the body mentions anything from outside itself.

| The lambda | Captures | Allocates |
| --- | --- | --- |
| `() -> doStatic()` | nothing | **No** |
| `() -> CONSTANT.run()` | nothing — a static field is not captured | **No** |
| `String::isEmpty` | nothing | **No** |
| `x -> x * 2` | nothing — `x` is a parameter, not a capture | **No** |
| `() -> localVar.run()` | `localVar` | **Yes** |
| `() -> this.count++` | `this` | **Yes** |
| `() -> count++` (a field) | `this`, implicitly | **Yes** |
| `someString::isEmpty` | `someString` | **Yes** |

Three of these catch people out:

**A parameter is not a capture.** `x -> x * 2` looks like it has state, and does not. The value
arrives as an argument each time the lambda runs, so the lambda itself holds nothing.

**Touching a field captures `this`.** Even though you never wrote `this`:

```java
private int count;

@ZeroAllocations
public Runnable tick() {
    return () -> count++;    // captures this. Allocates.
}
```

The lambda needs the instance in order to reach `count`, so the instance is captured. This is the
most common accidental capture in real code, because nothing in the syntax hints at it.

**Method references split two ways.** These differ by one word and are opposites:

```java
String::isEmpty      // unbound: the string arrives as an argument. Free.
someString::isEmpty  // bound: someString is captured. Allocates.
```

## Where it bites in real code

The pattern that turns one allocation into millions:

```java
@ZeroAllocations
public void onBatch(List<Order> orders) {
    orders.forEach(order -> route(order, session));   // captures this and session
}
```

That lambda is created once per call to `onBatch`, not once per order — but if `onBatch` runs per
message, that is one allocation per message. The loop body is not the problem; the lambda handed to
`forEach` is.

Also worth watching:

- **`Optional.orElseGet(() -> fallback(key))`** — captures `key`, allocates on the hot path even
  when the `Optional` is present.
- **`computeIfAbsent(key, k -> new Level(k, config))`** — captures `config`, so the lambda is
  allocated even on a cache *hit*, when the mapping function is never called. Drop the `config` and
  it captures nothing and costs nothing — the difference really is that fine.
- **A comparator built inline**, rather than held in a field.

## Fixing it

**Hoist it into a field**, so the allocation happens once at construction:

```java
private final Runnable task = () -> count++;   // allocated once

@ZeroAllocations
public Runnable tick() {
    return task;
}
```

**Or restructure so nothing is captured** — pass the state in as a parameter instead of closing
over it:

```java
// Captures nothing: the order arrives as an argument.
private static final BiConsumer<Session, Order> ROUTE = (session, order) -> route(order, session);
```

**Or do not use a lambda.** On a genuinely hot path, a plain loop allocates nothing and needs no
analysis to prove it:

```java
for (int i = 0; i < orders.size(); i++) {
    route(orders.get(i), session);
}
```

## What the checker reports

The fixture, `core/src/test/java/com/staticallocationchecker/fixtures/Lambdas.java`:

```java
@ZeroAllocations
public Runnable stateless() {
    return () -> NOOP.run();          // NOOP is a static field: captures nothing
}

@ZeroAllocations
public Runnable capturing(StringBuilder sink) {
    return () -> sink.append('x');    // captures sink
}
```

**One finding.** `stateless()` is reported as clean:

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.Lambdas` |
| `methodName` | `capturing` |
| `line` | `20` |
| `category` | `LAMBDA` |

This is one of the few places the checker can prove a *lack* of allocation rather than assuming the
worst. Everywhere else it is deliberately conservative; here the JVM's specification guarantees a
non-capturing lambda need not produce a fresh instance, so a clean verdict is a real one.
