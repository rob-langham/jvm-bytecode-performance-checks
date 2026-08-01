---
title: Allocation Scenarios
nav_order: 4
has_children: true
---

# Allocation Scenarios

One page per thing that puts an object on the heap, and per rule that decides what happens next.

Each page leads with **why** the allocation happens, then where it shows up in real code, then how
to avoid it. The finding the checker produces comes at the end. Nothing here asks you to read
bytecode — if a page cannot explain an allocation in Java terms, that is a problem with the page.

## Allocations with a `new` you can see

| Page | Category | Short version |
| --- | --- | --- |
| [Direct `new`](direct-new.md) | `NEW` | You wrote `new`. One finding per site; constructors are not descended into. |
| [Arrays](arrays.md) | `NEW_ARRAY` | Arrays are objects, and the JVM zeroes them for you. |

## Allocations with no `new` at all

These are the ones worth reading even if you never run the tool.

| Page | Category | Short version |
| --- | --- | --- |
| [Autoboxing](autoboxing.md) | `BOXING` | A primitive becoming an object. Any primitive meeting a generic API. |
| [String concatenation](string-concat.md) | `STRING_CONCAT` | Strings are immutable, so `+` must build a new one. |
| [Lambdas](lambdas.md) | `LAMBDA` | A lambda that captures anything is built fresh every time. |
| [Varargs](varargs.md) | `VARARGS_ARRAY` | The compiler builds an array at the call site, on your hot path. |

## How the checker reaches them

| Page | Short version |
| --- | --- |
| [Transitive calls](transitive-calls.md) | The contract covers everything the method calls, not just the method. |
| [Virtual dispatch](virtual-dispatch.md) | An interface call is checked against every implementation it can see. |
| [Inheritance](inheritance.md) | Both the code and the contract travel through a class hierarchy. |
| [Recursion](recursion.md) | Cycles terminate; each site is reported once. |

## What is allowed, and what is refused

| Page | Short version |
| --- | --- |
| [Exceptions](exceptions.md) | Allocating a `Throwable` is exempt. The message is not. |
| [Unanalyzable calls](unanalyzable-calls.md) | "I could not check this" — not "this allocates". |

## Allocating on purpose

| Page | Short version |
| --- | --- |
| [The warmup contract](warmup-contract.md) | Where allocation is allowed: guarded by a branch, and kept. |
| [What counts as cached](warmup-caching.md) | Fields, locals, collections, arrays — and what does not count. |
| [Where annotations can go](annotation-semantics.md) | Methods, constructors, types, and what each one covers. |

## About the examples

Everything on these pages comes from the fixtures in
`core/src/test/java/com/staticallocationchecker/fixtures/`, which are the same classes the test
suite asserts against. If a page shows a finding, that is the finding the tool produces — currently
34 across the whole corpus. See [Regenerating these pages](regenerating.md) to re-derive them.
