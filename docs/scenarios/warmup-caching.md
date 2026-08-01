---
title: What counts as cached
parent: Allocation Scenarios
nav_order: 14
---

# What counts as cached

**"Kept" means more than "assigned to a field".** Real warmup code fills pools and registers things
in collections, and all of that counts.

This page is the reference for what the [warmup contract](warmup-contract.md) accepts. Every
example on it is **compliant** — the whole fixture produces no findings. It exists to show you where
the edges are.

## The four ways to keep something

### Into a field

The obvious one. Instance or static, both fine:

```java
@AllocationsForWarmup
public Object staticField() {
    if (staticCache == null) {
        staticCache = new Object();
    }
    return staticCache;
}
```

### Via a local variable

```java
@AllocationsForWarmup
public Object throughLocal() {
    if (instanceCache == null) {
        Object created = new Object();   // into a local...
        instanceCache = created;         // ...then into the field
    }
    return instanceCache;
}
```

Accepted. The analysis follows the object through the local variable, so it still knows the thing
being stored is the thing that was allocated. This matters more than it sounds: writing it in two
steps is extremely common, and rejecting it would make the annotation useless.

### Into a collection or map held in a field

```java
private final List<Object> list = new ArrayList<>();
private final Map<String, Object> map = new HashMap<>();

@AllocationsForWarmup
public void intoCollection(boolean init) {
    if (init) {
        list.add(new Object());          // accepted
    }
}

@AllocationsForWarmup
public void intoMap(boolean init) {
    if (init) {
        map.put("k", new Object());      // accepted — both arguments count
    }
}
```

There is no field assignment here at all. The object counts as kept because it was handed to
something *already reachable from a field*. Registries, pools and caches are built this way, and
they are a primary reason the annotation exists.

**The receiver is what decides.** `list` is read from a field, so `list.add(x)` keeps `x`. If the
receiver were a local or a freshly-created object, it would not:

```java
new ArrayList<>().add(new Object());   // NOT kept — the list is thrown away
localList.add(new Object());           // NOT kept — the list may be too
```

{: .note }
> This is a heuristic, and knowingly so. `logger.debug(new Object())` on a field-held logger would
> be accepted as "kept" even though the logger keeps nothing. The stricter alternative — demanding a
> literal field store — rejects the pooling patterns this feature exists to serve, so the analysis
> errs towards accepting.

### Into an array element

```java
private Object[] pool;

@AllocationsForWarmup
public void intoArrayElements(int n) {
    if (pool == null) {
        pool = new Object[n];                // allocation 1
        for (int i = 0; i < n; i++) {
            pool[i] = new Object();          // allocation 2 — kept, via the array
        }
    }
}
```

Two allocations in one method, each judged separately, both compliant. Storing into the array keeps
the element; the array itself is kept because it is assigned to `pool`. Note the array is **not**
exempted just because things are stored into it — it has to justify itself on its own terms, and
here it does.

## What counts as guarded

Guardedness is "some path through the method skips this allocation", so it is not limited to `if`:

### A loop

```java
@AllocationsForWarmup
public void guardedByLoop(int n) {
    for (int i = 0; i < n; i++) {
        instanceCache = new Object();
    }
}
```

Accepted, because `n <= 0` skips the body entirely.

{: .warning }
> Be honest about this one. It allocates `n` times per call, and the checker passes it. "Some path
> skips it" is a weak guarantee when the path that does not skip it runs a thousand times. **If a
> warmup method contains a loop, the static result is weak evidence** — verify it with the
> [runtime recorder](../runtime/steady-state.md).

### A ternary

```java
@AllocationsForWarmup
public Object guardedByTernary() {
    instanceCache = instanceCache == null ? new Object() : instanceCache;
    return instanceCache;
}
```

Accepted — a ternary compiles to the same conditional jump an `if` does.

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

Accepted. Exception handlers create additional paths, and the analysis accounts for them.

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

Each allocation is judged on its own. Two compliant ones give no findings; one compliant and one not
gives exactly one finding, pointing at the one that failed.

## The whole thing on one page

| You wrote | Kept? |
| --- | --- |
| `field = new X()` | Yes |
| `X x = new X(); field = x;` | Yes |
| `fieldList.add(new X())` | Yes |
| `fieldMap.put(k, new X())` | Yes |
| `fieldArray[i] = new X()` | Yes |
| `return new X()` | **No** — `WARMUP_NOT_CACHED` |
| `localList.add(new X())` | **No** — receiver is not a field |
| `new ArrayList<>().add(new X())` | **No** — receiver is thrown away |

| You wrote | Guarded? |
| --- | --- |
| `if (field == null)` | Yes |
| `for` / `while` | Yes — the zero-iteration path skips it |
| a ternary | Yes |
| `switch` with a path that skips | Yes |
| nothing — straight-line code | **No** — `WARMUP_NOT_GUARDED` |
| an allocation as the method's very first instruction | **No** — nothing can precede it |
