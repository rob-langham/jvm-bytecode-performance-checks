---
title: Autoboxing
parent: Allocation Scenarios
nav_order: 3
---

# Autoboxing

The allocation with no `new`, no method call you wrote, and no visible presence in the source at
all. This is the one that most often puts a real allocation on a hot path.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/Autoboxing.java`

```java
public class Autoboxing {

    @ZeroAllocations
    public Object box(int n) {
        Integer boxed = n;
        return boxed;
    }
}
```

The assignment is the whole thing. There is no syntax here that looks like an allocation.

## The bytecode

```
  public java.lang.Object box(int);
    Code:
       0: iload_1
       1: invokestatic  #7                  // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;
       4: astore_2
       5: aload_2
       6: areturn
```

`javac` inserted `Integer.valueOf(int)`. In the source there is an assignment; in the bytecode
there is a static call that may return a heap object.

## What the checker reports

| Field | Value |
| --- | --- |
| `kind` | `ZERO_ALLOCATION_VIOLATION` |
| `className` | `com.staticallocationchecker.fixtures.Autoboxing` |
| `methodName` / `methodDescriptor` | `box` `(I)Ljava/lang/Object;` |
| `line` | `10` |
| `category` | `BOXING` |

## Why

`Allocations.isBoxing` matches the exact shape of a boxing conversion, and nothing else:

```java
return call.getOpcode() == Opcodes.INVOKESTATIC
        && WRAPPER_TYPES.contains(call.owner)          // Integer, Long, Short, Byte,
        && call.name.equals("valueOf")                 // Character, Boolean, Float, Double
        && Type.getArgumentTypes(call.desc).length == 1
        && Type.getArgumentTypes(call.desc)[0].getSort() <= Type.DOUBLE;   // primitive argument
```

The last two conditions matter. `Integer.valueOf(String)` and `Integer.valueOf(String, int)` are
parsing methods, not boxing conversions, and the arity plus primitive-argument check excludes them.

{: .note }
> **This is deliberately conservative.** `Integer.valueOf` returns a cached instance for values in
> `[-128, 127]`, and `Boolean.valueOf` never allocates at all. The checker still reports them,
> because whether a given call allocates depends on a runtime value it cannot see. A path that boxes
> only small integers today is one refactor away from boxing large ones. If you want the guarantee,
> do not box.

## Where it comes from in real code

Almost always a primitive meeting a generic API:

```java
@ZeroAllocations
public Level lookup(long instrumentId) {
    return levels.get(instrumentId);      // Map<Long, Level> — boxes on every call
}

@ZeroAllocations
public void record(int size) {
    sizes.add(size);                      // List<Integer> — boxes
    counters.merge(key, 1L, Long::sum);   // boxes the 1L, and the result
}
```

Also worth watching for:

- **Ternaries that mix types.** `flag ? 1 : someInteger` boxes the `1`.
- **`==` on wrappers**, which does not box but is usually a bug for the same underlying reason.
- **Varargs of primitives** — `String.format("%d", count)` boxes `count` *and* allocates
  [the varargs array](varargs.md).

## Fixing it

**Use a primitive-specialised collection.** Eclipse Collections, fastutil, Agrona and HPPC all
provide `LongObjectMap`-style containers that never box:

```java
// Before
private final Map<Long, Level> levels = new HashMap<>();

// After
private final Long2ObjectOpenHashMap<Level> levels = new Long2ObjectOpenHashMap<>(1024);
```

**Or keep the primitive all the way through.** Where the value is genuinely numeric and bounded, an
index into a preallocated array is both faster and trivially allocation-free.

**Or accept the cache.** If the values really are always small, `Integer.valueOf` is free — but the
checker will still flag it, and there is no suppression mechanism today, so the only way to make it
quiet is to remove the boxing.
