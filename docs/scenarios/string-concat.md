---
title: String concatenation
parent: Allocation Scenarios
nav_order: 4
---

# String concatenation

**Joining strings with `+` builds a new `String`, and a `String` is an object.**

Strings are immutable, so there is no such thing as appending to one. `a + b` cannot modify `a`; it
has to produce a third string containing both. That third string is the allocation.

## Why it is unavoidable

```java
String greeting = "hello ";
String message = greeting + name;   // greeting is unchanged. A new String now exists.
```

Immutability is the reason. If strings could be modified in place, `+` could be free; because they
cannot, every `+` on a runtime value produces a new object.

The exception is when the compiler can do the work at compile time. `"a" + "b"` where both sides
are constants becomes the single constant `"ab"` in the class file, with no runtime allocation at
all — so constant folding is free, and only concatenation involving a *variable* costs anything.

## Where it hides

**In logging — even logging that is switched off.** This is the classic:

```java
@ZeroAllocations
public void onTick(long id) {
    log.debug("tick " + id);     // the String is built BEFORE debug() is called
}
```

Java evaluates arguments before the call. The string is constructed, then `debug` decides the level
is disabled and throws it away. Guarding it helps at runtime:

```java
if (log.isDebugEnabled()) {
    log.debug("tick " + id);
}
```

but the checker will **still report it**, because it reports every reachable allocation and cannot
know your production log level. On a genuinely zero-allocation path, do not log.

**In exception messages.**

```java
throw new IllegalStateException("bad id " + id);
//        ^ exempt: allocating a Throwable is allowed
//                                     ^ NOT exempt: the message String is a normal allocation
```

The [exception exemption](exceptions.md) covers the exception, not everything on the line.

**In `toString()`**, which is easy to call by accident — string concatenation of any non-`String`
object calls it, and most implementations concatenate internally.

## Fixing it

**Do not build strings on the hot path.** Emit binary or fixed-layout records and format them
off-path, where allocation does not matter.

**Reuse a buffer** for the cases where you must produce text:

```java
private StringBuilder scratch;

@AllocationsForWarmup
StringBuilder scratch() {
    if (scratch == null) {
        scratch = new StringBuilder(256);
    }
    return scratch;
}

@ZeroAllocations
public void render(long id) {
    StringBuilder out = scratch();
    out.setLength(0);        // reuse, do not reallocate
    out.append("tick ").append(id);
}
```

Note that this only pays off if you consume the buffer without calling `toString()`, which would
allocate the very string you were avoiding.

**For exception messages on a hot path**, use a constant message. A preallocated exception is an
option, at the cost of a stale stack trace.

## What the checker reports

From `core/src/test/java/com/staticallocationchecker/fixtures/StringConcatenation.java`:

```java
@ZeroAllocations
public String concat(String a, int b) {
    return a + b;
}
```

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.StringConcatenation` |
| `methodName` | `concat` |
| `line` | `10` |
| `category` | `STRING_CONCAT` |

One finding, not two. Note that `b` is an `int` being joined to a `String` and is **not** boxed —
see below.

## In the bytecode

{: .note }
> Optional.

Since Java 9, `javac` compiles concatenation to a single `invokedynamic` that hands the pieces to
the JVM, which assembles an optimised routine on first use:

```
  public String concat(String a, int b);                   stack after      locals
       0: aload_1                                          [a]              0=this 1=a 2=b
       1: iload_2                                          [a, 5]           ^ a raw int, not
                                                                              an Integer
       2: invokedynamic makeConcatWithConstants            [String]
                        :(Ljava/lang/String;I)Ljava/lang/String;
                        ^^ pops both pieces, pushes ONE new String
       7: areturn                                          []
```

The descriptor `(Ljava/lang/String;I)` is worth reading: the `I` means the `int` is passed as a raw
`int`. Indified concatenation does not box its primitives, so this line is one allocation, not two.

The checker recognises it by the bootstrap method's owner being `StringConcatFactory`, rather than
by the call site's name.

{: .note }
> On Java 8 the same source compiled to `new StringBuilder()`, two `append` calls and a
> `toString()` — which this checker would report as a [`NEW`](direct-new.md) plus a string of
> unresolvable calls. The `invokedynamic` form is both faster and easier to recognise.
