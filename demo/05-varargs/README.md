# 05 — Varargs

A varargs call site allocates an array you never wrote, on every call.

```bash
./gradlew -p demo :05-varargs:checkStaticAllocation
```

## Expected: 2 findings

| Method | Category |
| --- | --- |
| `publish` | `VARARGS_ARRAY` |
| `publishExplicitArray` | `NEW_ARRAY` |

## Why two categories for identical bytecode

`emit(a, b)` and `emitArray(new long[] {a, b})` compile to **exactly the same instructions**. There
is no shape to pattern-match. The only signal is `ACC_VARARGS` on the callee's declaration, so the
checker resolves the target and asks.

The distinction earns its keep because the fixes differ: a varargs array usually goes away by
adding an arity-specific overload (`publishViaOverload` shows it), an explicit array by hoisting it
out of the hot path.

## Not reported

- `publishExisting` — the array already exists, so passing it allocates nothing.
- `publishViaOverload` — no array to synthesise. Ugly, and exactly why you want the checker to tell
  you the ugliness is necessary.

When the callee cannot be resolved, the array is reported as the less specific `NEW_ARRAY` rather
than guessed at — still flagged, just not sub-classified.
