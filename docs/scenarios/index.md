---
title: Allocation Scenarios
nav_order: 4
has_children: true
---

# Allocation Scenarios

One page per thing that can put an object on the heap, and per rule that decides what happens next.
Each page shows the same four things:

1. **The Java** — what it looks like in source.
2. **The bytecode** — what `javac` actually emitted, from `javap -c`.
3. **What the checker reports** — the real finding, field by field.
4. **Why** — the rule in [`Allocations.categoryOf`](https://github.com/rob-langham/jvm-bytecode-performance-checks/blob/main/core/src/main/java/com/staticallocationchecker/Allocations.java)
   or [`AllocationChecker`](https://github.com/rob-langham/jvm-bytecode-performance-checks/blob/main/core/src/main/java/com/staticallocationchecker/AllocationChecker.java)
   that produced it, and what to do about it.

Everything on these pages is generated from the fixtures in
`core/src/test/java/com/staticallocationchecker/fixtures/`, which are the same classes the test
suite asserts against. Nothing here is illustrative — if a page shows a finding, that is the
finding the tool produces. See [Regenerating these pages](regenerating.md).

## The allocating instructions

| Scenario | Category | The bytecode that gives it away |
| --- | --- | --- |
| [Direct `new`](direct-new.md) | `NEW` | `new` |
| [Arrays](arrays.md) | `NEW_ARRAY` | `newarray`, `anewarray`, `multianewarray` |
| [Autoboxing](autoboxing.md) | `BOXING` | `invokestatic Integer.valueOf` and friends |
| [String concatenation](string-concat.md) | `STRING_CONCAT` | `invokedynamic` bootstrapped by `StringConcatFactory` |
| [Lambdas](lambdas.md) | `LAMBDA` | `invokedynamic` bootstrapped by `LambdaMetafactory`, *with captured arguments* |
| [Varargs](varargs.md) | `NEW_ARRAY` | an `anewarray`/`newarray` you did not write |

## How the walk reaches them

| Scenario | What it demonstrates |
| --- | --- |
| [Transitive calls](transitive-calls.md) | The annotated method allocates nothing; a helper does |
| [Virtual dispatch](virtual-dispatch.md) | Resolution follows the call to every reachable implementation |
| [Inheritance](inheritance.md) | A method inherited rather than declared on the receiver's type |
| [Recursion](recursion.md) | The walk terminates, and reports once |
| [Shared helpers](transitive-calls.md#one-helper-two-entry-points) | One helper, two entry points, two findings |

## What is exempt, and what is refused

| Scenario | Outcome |
| --- | --- |
| [Exceptions](exceptions.md) | Allocating a `Throwable` is exempt |
| [Unanalyzable calls](unanalyzable-calls.md) | A call resolving to nothing is flagged, not assumed clean |

## The warmup contract

| Scenario | What it covers |
| --- | --- |
| [The warmup contract](warmup-contract.md) | Compliant, `WARMUP_NOT_GUARDED`, `WARMUP_NOT_CACHED` |
| [Warmup caching shapes](warmup-caching.md) | Field stores, locals, collections, maps, arrays, loops, ternaries |
| [The warmup boundary](warmup-contract.md#the-boundary) | Why a hot path calling a warmup method is clean |

## Annotation semantics

| Scenario | What it covers |
| --- | --- |
| [Annotation semantics](annotation-semantics.md) | Type-level, constructors, static initialisers, overrides, both annotations at once |
