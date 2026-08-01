---
title: Warmup caching shapes
parent: Allocation Scenarios
nav_order: 14
---

# Warmup caching shapes

"Cached into a field" is the plain statement of the rule. Real warmup code retains objects in more
ways than a direct `putfield`, and the checker recognises four shapes of retention and any control
structure that can skip the allocation.

Every method on this page is from
`core/src/test/java/com/staticallocationchecker/fixtures/WarmupCaching.java`, and **every one of
them is compliant** — the whole file produces no findings. It exists to pin down what the analysis
accepts.

## Retention shapes

### Direct field store

```java
private static Object staticCache;

@AllocationsForWarmup
public Object staticField() {
    if (staticCache == null) {
        staticCache = new Object();
    }
    return staticCache;
}
```

`PUTSTATIC` and `PUTFIELD` are both accepted:

```java
if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
    retained.addAll(frame.getStack(frame.getStackSize() - 1).insns);
    return;
}
```

The top of stack at the store is the value being stored; every instruction that could have produced
it is marked retained.

### Through a local variable

```java
@AllocationsForWarmup
public Object throughLocal() {
    if (instanceCache == null) {
        Object created = new Object();
        instanceCache = created;
    }
    return instanceCache;
}
```

```
       4: ifnonnull     20
       7: new           #2                  // class java/lang/Object
      10: dup
      11: invokespecial #1                  // Method java/lang/Object."<init>":()V
      14: astore_1                   <-- into a local
      15: aload_0
      16: aload_1                    <-- back out of the local
      17: putfield      #27                 // Field instanceCache:Ljava/lang/Object;
```

The value reaching `putfield` was produced by `aload_1`, not by `new`. This is precisely what the
`copyOperation` override in [the warmup contract](warmup-contract.md#how-caching-is-decided) exists
for: the `SourceValue` is passed through copies unchanged, so it still names the `NEW` at offset 7.

### Into a field-held collection or map

```java
private final List<Object> list = new ArrayList<>();
private final Map<String, Object> map = new HashMap<>();

@AllocationsForWarmup
public void intoCollection(boolean init) {
    if (init) {
        list.add(new Object());
    }
}

@AllocationsForWarmup
public void intoMap(boolean init) {
    if (init) {
        map.put("k", new Object());
    }
}
```

```
       1: ifeq          21
       4: aload_0
       5: getfield      #10                 // Field list:Ljava/util/List;   <-- receiver is a field read
       8: new           #2                  // class java/lang/Object
      11: dup
      12: invokespecial #1
      15: invokeinterface #30,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z
```

There is no `putfield` here at all. The object is retained because it was handed to something
already reachable from a field:

```java
if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)
        && insn instanceof MethodInsnNode call) {
    int argumentCount = Type.getArgumentTypes(call.desc).length;
    int receiverIndex = frame.getStackSize() - argumentCount - 1;
    if (receiverIndex < 0 || !isReadFromAField(frame.getStack(receiverIndex))) {
        return;
    }
    for (int argument = 0; argument < argumentCount; argument++) {
        retained.addAll(frame.getStack(frame.getStackSize() - 1 - argument).insns);
    }
}
```

**The receiver being a field read is what carries the weight.** `isReadFromAField` requires that
*every* instruction that could have produced the receiver is a `GETFIELD` or `GETSTATIC`. That is
what distinguishes `list.add(x)` — where `list` is durable state — from `new ArrayList<>().add(x)`
or `temporary.add(x)`, where the object is dropped as soon as the method returns.

All arguments of such a call are treated as retained, not just the last. `map.put("k", value)`
retains both.

{: .note }
> This is a heuristic, and knowingly so. `logger.debug(new Object())` on a field-held logger would
> count as retention. The alternative — requiring a literal field store — rejects the pooling and
> registry patterns that real warmup code is full of, so the analysis errs towards accepting.

### Into an array element

```java
private Object[] pool;

@AllocationsForWarmup
public void intoArrayElements(int n) {
    if (pool == null) {
        pool = new Object[n];
        for (int i = 0; i < n; i++) {
            pool[i] = new Object();
        }
    }
}
```

```java
if (opcode == Opcodes.AASTORE) {
    retained.addAll(frame.getStack(frame.getStackSize() - 1).insns);
    return;
}
```

Note what this does *not* do: it does not require the array itself to be cached. As the source
comment puts it, the array "must still justify itself separately — it is an allocation too, and gets
its own verdict". Here it does: `pool = new Object[n]` is guarded by `if (pool == null)` and stored
with `putfield`. Two allocations in one method, each independently compliant.

## Guard shapes

Guardedness is a reachability property, not a syntactic one, so anything that produces a
control-flow edge around the allocation qualifies.

### A loop

```java
@AllocationsForWarmup
public void guardedByLoop(int n) {
    for (int i = 0; i < n; i++) {
        instanceCache = new Object();
    }
}
```

```
       2: iload_2
       3: iload_1
       4: if_icmpge     24         <-- with n <= 0, jumps straight to return
       7: aload_0
       8: new           #2
      ...
      15: putfield      #27                 // Field instanceCache:Ljava/lang/Object;
      18: iinc          2, 1
      21: goto          2
      24: return
```

Compliant, because `n <= 0` reaches `return` at offset 24 without touching offset 8. Worth being
clear-eyed about: this method allocates `n` times per call, and the checker passes it. The contract
is "some path skips the allocation", which a loop trivially satisfies. If your warmup method has a
loop in it, the static result is weak evidence and the [runtime
recorder](../runtime/steady-state.md) is the thing to trust.

### A ternary

```java
@AllocationsForWarmup
public Object guardedByTernary() {
    instanceCache = instanceCache == null ? new Object() : instanceCache;
    return instanceCache;
}
```

A ternary compiles to the same conditional jump an `if` does, so it reads identically to the
analysis.

### Inside a try block

```java
@AllocationsForWarmup
public Object insideTryBlock() {
    if (instanceCache == null) {
        try {
            instanceCache = new Object();
        } catch (RuntimeException e) {
            instanceCache = null;
        }
    }
    return instanceCache;
}
```

Exception handlers add edges to the graph, and `newControlFlowEdge` records the ones ASM's analyser
follows. The allocation stays guarded by the outer `if`.

### Several allocations in one method

```java
@AllocationsForWarmup
public void twoCompliantAllocations() {
    if (instanceCache == null) {
        instanceCache = new Object();
    }
    if (staticCache == null) {
        staticCache = new Object();
    }
}
```

Each allocation is judged on its own — `exitReachableAvoiding` is called per site, avoiding only
that site. Two compliant allocations, no findings. One compliant and one not gives exactly one
finding.

## Summary

| Shape | Retained? | Why |
| --- | --- | --- |
| `field = new X()` | Yes | `PUTFIELD` / `PUTSTATIC` |
| `X x = new X(); field = x;` | Yes | copy-preserving interpreter |
| `fieldList.add(new X())` | Yes | receiver is a field read |
| `fieldMap.put(k, new X())` | Yes | all arguments retained |
| `fieldArray[i] = new X()` | Yes | `AASTORE` |
| `return new X()` | **No** | `WARMUP_NOT_CACHED` |
| `new ArrayList<>().add(new X())` | **No** | receiver is not a field read |
| `localList.add(new X())` | **No** | receiver is not a field read |

| Guard | Guarded? |
| --- | --- |
| `if (field == null)` | Yes |
| `for (…)` / `while (…)` | Yes — the zero-iteration path skips it |
| ternary | Yes |
| `switch` with a default that skips | Yes |
| nothing | **No** — `WARMUP_NOT_GUARDED` |
| allocation at instruction 0 | **No** — `exitReachableAvoiding` returns `false` for `avoid == 0` |
