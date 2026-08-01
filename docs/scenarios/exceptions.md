---
title: Exceptions
parent: Allocation Scenarios
nav_order: 11
---

# Exceptions

Allocating a `Throwable` is exempt. The exceptional path is not the hot path, and forcing people to
preallocate exceptions costs them stack traces for no real benefit.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/ExceptionAllocation.java`

```java
public class ExceptionAllocation {

    /** A user-defined exception, to exercise the index-then-reflection hierarchy climb. */
    public static class CustomException extends RuntimeException {
    }

    @ZeroAllocations
    public void throwsJdkException(boolean fail) {
        if (fail) {
            throw new IllegalStateException("bad state");
        }
    }

    @ZeroAllocations
    public void throwsCustomException(boolean fail) {
        if (fail) {
            throw new CustomException();
        }
    }

    @ZeroAllocations
    public Object allocatesObject() {
        return new Object();
    }
}
```

## The bytecode

All three methods contain a `new`. They are not distinguishable by opcode:

```
  public void throwsJdkException(boolean);
    Code:
       0: iload_1
       1: ifeq          14
       4: new           #7                  // class java/lang/IllegalStateException
       7: dup
       8: ldc           #9                  // String bad state
      10: invokespecial #11                 // Method java/lang/IllegalStateException."<init>":(Ljava/lang/String;)V
      13: athrow
      14: return

  public void throwsCustomException(boolean);
    Code:
       0: iload_1
       1: ifeq          12
       4: new           #14                 // class com/…/ExceptionAllocation$CustomException
       7: dup
       8: invokespecial #16                 // Method com/…/ExceptionAllocation$CustomException."<init>":()V
      11: athrow
      12: return

  public java.lang.Object allocatesObject();
    Code:
       0: new           #2                  // class java/lang/Object
       3: dup
       4: invokespecial #1                  // Method java/lang/Object."<init>":()V
       7: areturn
```

The distinction is the *operand*: the class named by the `new`.

## What the checker reports

One finding. Both `throw` sites are exempt:

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.ExceptionAllocation` |
| `methodName` | `allocatesObject` |
| `line` | `28` |
| `category` | `NEW` |

## Why

The `NEW` case tests the allocated type before classifying:

```java
case Opcodes.NEW:
    return isThrowableType.test(((TypeInsnNode) insn).desc) ? null : AllocationCategory.NEW;
```

`AllocationChecker.isThrowable` climbs the supertype chain in two stages:

```java
private boolean isThrowable(String internalName, Map<String, ClassNode> index) {
    String current = internalName;
    while (current != null) {
        if (current.equals("java/lang/Throwable")) {
            return true;
        }
        if (current.equals("java/lang/Object")) {
            return false;
        }
        ClassNode node = index.get(current);
        if (node != null) {
            current = node.superName;                        // in the roots: read superName
        } else {
            return Allocations.isThrowableByReflection(      // outside: ask the classloader
                    current, getClass().getClassLoader());
        }
    }
    return false;
}
```

That is what the two fixtures exercise:

- **`CustomException`** *is* in the analysis roots, so the loop reads its `superName`
  (`java/lang/RuntimeException`), which is not — and falls through to reflection.
- **`IllegalStateException`** is never in the roots, so the first lookup misses and reflection
  answers immediately.

`isThrowableByReflection` loads the class with `initialize = false` and asks
`Throwable.class.isAssignableFrom`. It catches `Throwable` and returns `false` on any failure, so a
type that cannot be loaded is treated as a normal allocation — conservative in the right direction.

## What is *not* exempt

The exemption is narrow: it covers the `new` of a `Throwable` subtype, and nothing else on the line.

```java
@ZeroAllocations
public void validate(long id) {
    if (id < 0) {
        throw new IllegalStateException("bad id " + id);
        //        ^ exempt (NEW of a Throwable)
        //                                   ^ NOT exempt: STRING_CONCAT allocates a String
    }
}
```

Also not exempt:

- **Arrays of throwables.** `new IllegalStateException[4]` is `NEW_ARRAY`; there is no exemption
  path for the array opcodes, and an array is not something you throw.
- **Anything the constructor does.** The checker does not descend into `<init>` (see
  [direct `new`](direct-new.md#why)), so a constructor that allocates internally is invisible here —
  which cuts both ways.
- **Suppression and cause chains built on the hot path.**

## A note on the cost you are exempting

`fillInStackTrace` — which runs in `Throwable`'s constructor — is usually far more expensive than
the allocation it accompanies, and its cost scales with stack depth. The exemption exists because
exceptions should not be on the hot path at all, not because throwing is cheap.

If you find an exception being thrown *and caught* inside a zero-allocation path as flow control,
the checker will say nothing, and it is still the most expensive thing in the method. Overriding
`fillInStackTrace` to return `this` is the usual remedy, at the cost of the trace.
