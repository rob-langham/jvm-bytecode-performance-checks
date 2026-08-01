---
title: Arrays
parent: Allocation Scenarios
nav_order: 2
---

# Arrays

Three different opcodes allocate arrays, depending on the element type and the number of
dimensions. All three are `NEW_ARRAY`.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/ArrayAllocations.java`

```java
public class ArrayAllocations {

    @ZeroAllocations
    public Object primitiveArray() {
        return new int[10];
    }

    @ZeroAllocations
    public Object referenceArray() {
        return new String[10];
    }

    @ZeroAllocations
    public Object multiArray() {
        return new int[2][3];
    }
}
```

## The bytecode

```
  public java.lang.Object primitiveArray();
    Code:
       0: bipush        10
       2: newarray       int
       4: areturn

  public java.lang.Object referenceArray();
    Code:
       0: bipush        10
       2: anewarray     #7                  // class java/lang/String
       5: areturn

  public java.lang.Object multiArray();
    Code:
       0: iconst_2
       1: iconst_3
       2: multianewarray #9,  2             // class "[[I"
       6: areturn
```

| Opcode | Used for | Operand |
| --- | --- | --- |
| `newarray` | One-dimensional array of a **primitive** | An immediate type code (`int`, `long`, …) |
| `anewarray` | One-dimensional array of a **reference** type | A constant-pool class reference |
| `multianewarray` | A **multi-dimensional** array | Class reference plus the number of dimensions to allocate |

Unlike `new`, none of these needs a paired constructor call: an array's elements are zeroed by the
JVM, so the single instruction is the whole allocation.

## What the checker reports

Three findings, one per method:

| `methodName` | `line` | `category` | Instruction |
| --- | --- | --- | --- |
| `primitiveArray` | 10 | `NEW_ARRAY` | `newarray int` |
| `referenceArray` | 15 | `NEW_ARRAY` | `anewarray java/lang/String` |
| `multiArray` | 20 | `NEW_ARRAY` | `multianewarray [[I, 2` |

Note that `multiArray` is **one** finding, not three. `new int[2][3]` allocates one outer array and
two inner ones — three objects — but it is a single instruction, and the checker reports
instructions. The distinction matters if you are counting objects rather than sites; for the purpose
of "does this path allocate", one is enough.

## Why

```java
case Opcodes.NEWARRAY:
case Opcodes.ANEWARRAY:
case Opcodes.MULTIANEWARRAY:
    return AllocationCategory.NEW_ARRAY;
```

There is no exemption path here, unlike `NEW`. An array of `Throwable` is still an array
allocation: the exemption is for *throwing*, and an array is not something you throw.

## Where these show up when you did not write them

The `NEW_ARRAY` findings that surprise people rarely have a `new` in the source:

- **[Varargs call sites](varargs.md)** — `javac` synthesises an `anewarray` or `newarray` per call.
- **Collection growth** — `ArrayList.add` past capacity allocates a new backing array, though you
  will usually see that as an [unanalyzable call](unanalyzable-calls.md) rather than a `NEW_ARRAY`,
  because `java.util` is not in your analysis roots.
- **`String.toCharArray()`, `Arrays.copyOf`, `clone()`** — same again.

## Fixing it

Preallocate and reuse. This is the canonical warmup shape:

```java
private long[] buffer;

@AllocationsForWarmup
long[] buffer() {
    if (buffer == null) {
        buffer = new long[64];
    }
    return buffer;
}
```

The array allocation is still there; it is now guarded, cached, and sanctioned. See
[the warmup contract](warmup-contract.md), and [Warmup under load](../runtime/steady-state.md) for
how to confirm it fires once and stops.
