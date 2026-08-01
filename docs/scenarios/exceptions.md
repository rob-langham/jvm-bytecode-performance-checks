---
title: Exceptions
parent: Allocation Scenarios
nav_order: 11
---

# Exceptions

**Allocating an exception is allowed. The checker deliberately ignores it.**

`new IllegalStateException(...)` is an allocation like any other, and it is exempt — the only
exemption in the tool.

## Why

Because the exceptional path is not the hot path. If you are throwing, the request has already
failed; one allocation is not what you should be worrying about.

The alternative would be worse. Without the exemption, satisfying the checker would mean
preallocating exception instances as constants — and a preallocated exception carries the stack
trace from wherever it was first created, which is useless and actively misleading during an
incident. Forcing that trade on people to satisfy a static check would be a bad bargain.

```java
@ZeroAllocations
public void validate(boolean fail) {
    if (fail) {
        throw new IllegalStateException("bad state");   // no finding
    }
}
```

The exemption applies to any subtype of `Throwable`, including your own:

```java
public static class CustomException extends RuntimeException {
}

@ZeroAllocations
public void validate(boolean fail) {
    if (fail) {
        throw new CustomException();                    // no finding
    }
}
```

The checker works out whether a type is a `Throwable` by climbing its supertypes — through your own
code where it can see it, and by asking the classloader when it cannot. A type it cannot resolve at
all is treated as a normal allocation, which is the safe direction to be wrong in.

## What is *not* exempt

The exemption is narrow: it covers the `new` of a `Throwable`, and nothing else on the line.

**The message.** This is the one people hit:

```java
throw new IllegalStateException("bad id " + id);
//        ^^^^^^^^^^^^^^^^^^^^^ exempt
//                              ^^^^^^^^^^^^^^ NOT exempt — STRING_CONCAT
```

A constant message is free; a built one is an allocation. See
[string concatenation](string-concat.md).

**Arrays of throwables.** `new IllegalStateException[4]` is a normal `NEW_ARRAY`. The exemption is
for throwing, and an array is not something you throw.

**Anything else on the line** — a boxed value in the message, a varargs array from
`String.format`.

## What the exemption is not telling you

**Throwing is not cheap.** The expensive part of an exception is not the allocation at all; it is
`fillInStackTrace()`, which runs inside `Throwable`'s constructor and walks the entire call stack.
Its cost scales with stack depth and typically dwarfs the allocation.

So the exemption exists because exceptions *should not be on the hot path*, not because they are
free when they are.

If you are using exceptions as control flow inside a zero-allocation method — throwing and catching
them in normal operation — the checker will say nothing, and it will still be the most expensive
thing in the method. The usual remedy is a custom exception overriding `fillInStackTrace()` to
return `this`, trading the stack trace for speed. That is a decision to take deliberately, not to
back into.

**Constructors are not walked.** The checker does not descend into `<init>`, so whatever your
exception's constructor does — building a message, capturing context, allocating a details object —
is invisible here.

## What the checker reports

From `core/src/test/java/com/staticallocationchecker/fixtures/ExceptionAllocation.java`, which has
three methods that all contain a `new`:

| `methodName` | Allocates | Reported |
| --- | --- | --- |
| `throwsJdkException` | `new IllegalStateException("bad state")` | **No** — exempt |
| `throwsCustomException` | `new CustomException()` | **No** — exempt |
| `allocatesObject` | `new Object()` | **Yes**, line 28, `NEW` |

One finding from three `new` instructions. The opcode is identical in all three cases — what
differs is the type being allocated.
