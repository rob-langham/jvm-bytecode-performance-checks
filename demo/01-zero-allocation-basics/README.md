# 01 — Zero-allocation basics

Every allocation category the checker recognises, written the way each actually turns up in real
code. Only the first has a visible `new`; the rest are the ones review misses.

```bash
./gradlew -p demo :01-zero-allocation-basics:checkStaticAllocation
```

## Expected: 6 findings

| Method | Category | Why |
| --- | --- | --- |
| `directNew` | `NEW` | The obvious one |
| `scratchBuffer` | `NEW_ARRAY` | A buffer allocated per call instead of reused |
| `lookup` | `BOXING` | `Map<Long, …>` boxes the primitive key on every lookup |
| `lookup` | `UNANALYZABLE_CALL` | `Map.get` is in the JDK, outside the analysis roots |
| `describe` | `STRING_CONCAT` | An `invokedynamic`, invisible in the source |
| `onFill` | `LAMBDA` | Captures `price`, so a new instance per evaluation |

## Deliberately *not* reported

- `noOp()` — a non-capturing lambda links to a cached singleton and allocates nothing.
- `reject()` — `Throwable` allocation is exempt; the exceptional path is not the hot path.

Those two exemptions are why the checker is usable: without them, every guard clause and every
callback would be noise.
