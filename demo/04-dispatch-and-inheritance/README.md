# 04 — Dispatch and inheritance

The case a naive checker gets wrong: **the allocation is nowhere near the annotated method.**

Resolving a call site against only the type it names finds an abstract declaration with an empty
body, concludes "allocates nothing", and reports clean. That is the most dangerous answer a
verification tool can give, because it looks like success.

```bash
./gradlew -p demo :04-dispatch-and-inheritance:checkStaticAllocation
```

## Expected: 5 findings

- **`BoxingHandler.handle`** — allocation behind an interface. Reported **twice**: once reached
  through `dispatch()`, and once as an entry point in its own right, because it inherits
  `@ZeroAllocations` from the interface without repeating it. Each finding carries the call path
  that reached it — one allocation, two ways in.
- **`BaseProcessor.shared`** — declared on a superclass and inherited unchanged by
  `DerivedProcessor`. The call site names the subclass; resolution climbs to find the body.
- Two `UNANALYZABLE_CALL`s for `Long.longValue()`, which is in the JDK.

## What this demonstrates

1. **Virtual dispatch is followed** to every implementation present in the analysis roots.
2. **The contract is inherited.** `Handler` declares `@ZeroAllocations` once; implementations are
   bound by it without restating it. Java does not inherit annotations — the checker consults the
   supertype anyway, because otherwise overriding a method would silently drop its contract.
3. **Findings are attributed to the code that allocates**, not the call site, so the fix lands
   where the problem is.

`DoublingHandler` implements the same interface cleanly and is not reported.
