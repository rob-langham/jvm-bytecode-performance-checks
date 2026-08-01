---
title: String concatenation
parent: Allocation Scenarios
nav_order: 4
---

# String concatenation

`a + b` on strings is an `invokedynamic` in modern Java, and it allocates.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/StringConcatenation.java`

```java
public class StringConcatenation {

    @ZeroAllocations
    public String concat(String a, int b) {
        return a + b;
    }
}
```

## The bytecode

```
  public java.lang.String concat(java.lang.String, int);
    Code:
       0: aload_1
       1: iload_2
       2: invokedynamic #7,  0              // InvokeDynamic #0:makeConcatWithConstants:(Ljava/lang/String;I)Ljava/lang/String;
       7: areturn
```

Since Java 9 ([JEP 280](https://openjdk.org/jeps/280)) `javac` compiles string concatenation to a
single `invokedynamic` whose bootstrap method is `StringConcatFactory.makeConcatWithConstants`. The
JVM links it at first execution to a `MethodHandle` chain that does the work.

{: .note }
> On Java 8 the same source compiled to `new StringBuilder(); append(); append(); toString()` —
> which this checker would report as [`NEW`](direct-new.md) plus a string of unresolvable calls. The
> indy form is both faster and easier to recognise, and it is what you will see on any supported JDK.

Note what the descriptor tells you: `(Ljava/lang/String;I)Ljava/lang/String;` — the `int` is passed
as an `int`. Indified concatenation does *not* box its primitive arguments, so this is one
allocation, not two.

## What the checker reports

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.StringConcatenation` |
| `methodName` / `methodDescriptor` | `concat` `(Ljava/lang/String;I)Ljava/lang/String;` |
| `line` | `10` |
| `category` | `STRING_CONCAT` |

## Why

The check is on the bootstrap method's owner, not on the call site's name:

```java
private static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

if (STRING_CONCAT_FACTORY.equals(indy.bsm.getOwner())) {
    return AllocationCategory.STRING_CONCAT;
}
```

`makeConcatWithConstants` produces a new `String`, and a `String` is an object. There is no
interning path that would let the checker prove otherwise for a runtime-computed value.

## Where it hides

**In logging that is switched off.** This is the classic:

```java
@ZeroAllocations
public void onTick(long id) {
    log.debug("tick " + id);    // allocates whether or not DEBUG is enabled
}
```

The concatenation is evaluated to produce the argument, *then* `debug` decides to throw it away.
Parameterised logging fixes the concatenation but introduces [a varargs array](varargs.md) instead;
the only allocation-free answer on a hot path is a guard:

```java
if (log.isDebugEnabled()) {
    log.debug("tick " + id);
}
```

which moves the allocation onto a path that a production configuration does not take. Note that the
static checker will **still report it** — it reports every reachable site, and `isDebugEnabled()`
being false is a runtime property. On a genuinely zero-allocation path, do not log.

**In exception messages.** `throw new IllegalStateException("bad id " + id)` allocates the
[exception (exempt)](exceptions.md) and the message `String` (**not** exempt — the exemption is for
`Throwable` subtypes, and `String` is not one).

**In `toString()`, `String.format`, and string switches on computed values.**

## Fixing it

- Do not build strings on the hot path. Emit binary or fixed-layout records and format them
  off-path.
- For diagnostics, write into a reusable `StringBuilder` or byte buffer held in a field, behind a
  [warmup boundary](warmup-contract.md).
- For exception messages on a path that must not allocate, throw a preallocated exception with a
  constant message — but note the trade-off, since a preallocated `Throwable` carries a stale stack
  trace.
