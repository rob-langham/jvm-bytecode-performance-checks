---
title: Varargs
parent: Allocation Scenarios
nav_order: 6
---

# Varargs

Calling a varargs method allocates an array at the *call site*. The array is invisible in the
source and belongs to the caller, not the callee.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/Varargs.java`

```java
public class Varargs {

    static int count(int... values) {
        return values.length;
    }

    static int countObjects(Object... values) {
        return values.length;
    }

    @ZeroAllocations
    public int passesPrimitiveVarargs() {
        return count(1, 2, 3);
    }

    @ZeroAllocations
    public int passesObjectVarargs(String a, String b) {
        return countObjects(a, b);
    }

    /** Passing an existing array to a varargs parameter allocates nothing. */
    @ZeroAllocations
    public int passesExistingArray(int[] existing) {
        return count(existing);
    }
}
```

## The bytecode

```
  public int passesPrimitiveVarargs();
    Code:
       0: iconst_3
       1: newarray       int          // <-- the array you did not write
       3: dup
       4: iconst_0
       5: iconst_1
       6: iastore
       7: dup
       8: iconst_1
       9: iconst_2
      10: iastore
      11: dup
      12: iconst_2
      13: iconst_3
      14: iastore
      15: invokestatic  #7            // Method count:([I)I
      18: ireturn
```

`javac` emits: allocate an array of the right length, fill it element by element, pass it. The
callee's signature was `([I)I` all along — varargs is purely a call-site convenience.

The `Object...` case is the same with `anewarray`:

```
  public int passesObjectVarargs(java.lang.String, java.lang.String);
    Code:
       0: iconst_2
       1: anewarray     #2                  // class java/lang/Object
       4: dup
       ...
      12: invokestatic  #13                 // Method countObjects:([Ljava/lang/Object;)I
```

And passing an array through allocates nothing at all, because there is nothing to synthesise:

```
  public int passesExistingArray(int[]);
    Code:
       0: aload_1
       1: invokestatic  #7                  // Method count:([I)I
       4: ireturn
```

## What the checker reports

Two findings. `passesExistingArray` is clean.

| `methodName` | `line` | `category` |
| --- | --- | --- |
| `passesPrimitiveVarargs` | 18 | `NEW_ARRAY` |
| `passesObjectVarargs` | 23 | `NEW_ARRAY` |

{: .warning }
> **`AllocationCategory` declares `VARARGS_ARRAY`, and nothing produces it.** A synthesised varargs
> array is reported as `NEW_ARRAY`, indistinguishable from an array you wrote yourself. This is a
> known gap with a `@Disabled` test naming it. Until it is closed, a `NEW_ARRAY` finding on a line
> with no visible array construction almost certainly means varargs.

## Why

The checker sees the instruction, not the intent. `newarray`/`anewarray` is `NEW_ARRAY`
regardless of whether you or `javac` wrote it — which is precisely the value of checking bytecode
instead of source. Distinguishing the two would mean recognising the synthesise-fill-call idiom, or
reading the callee's `ACC_VARARGS` flag at the call site.

## Where it hides

Varargs is pervasive in APIs that look innocuous:

```java
@ZeroAllocations
public void handle(long id, int size) {
    log.info("received {} of {}", id, size);   // Object[] + two boxed longs/ints
    String.format("%d/%d", id, size);          // Object[] + boxing + STRING_CONCAT machinery
    List.of(a, b, c);                          // Object[] for 3+ elements
    Arrays.asList(a, b);                       // Object[]
    Objects.hash(a, b);                        // Object[]
    EnumSet.of(A, B, C, D, E, F);              // Object[] for the 6+ overload
}
```

Two of those are worth calling out:

- **`List.of` and `Set.of`** have overloads for zero to ten arguments that take individual
  parameters, then a varargs overload. `List.of(a, b, c)` uses a fixed-arity overload and allocates
  no array (though it does allocate the list itself). `List.of(a, b, …, k)` with eleven arguments
  hits the varargs overload and allocates one.
- **`Objects.hash(a, b)`** always allocates an array. `Objects.hashCode(a) * 31 + Objects.hashCode(b)`
  does not.

## Fixing it

**Use a fixed-arity overload.** Most logging frameworks provide one- and two-argument forms
precisely to avoid this; SLF4J's `info(String, Object, Object)` allocates no array (though it will
[box](autoboxing.md) primitives).

**Write the fixed-arity method yourself:**

```java
// Before
static int count(int... values) { ... }
count(1, 2, 3);

// After
static int count(int a, int b, int c) { ... }
count(1, 2, 3);   // no array
```

**Pass a preallocated array** held in a field, filled in place — the `passesExistingArray` shape
above. The array itself becomes a [warmup allocation](warmup-contract.md).
