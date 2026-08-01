# 02 — A clean hot path

The same job as scenario 01, written to allocate nothing. **This is the only scenario that fails
its build on a finding** — the way a real project would configure it.

```bash
./gradlew -p demo :02-clean-hot-path:checkStaticAllocation
```

## Expected: no findings, build passes

Each technique answers a specific finding from scenario 01:

| Scenario 01 | Here |
| --- | --- |
| `BOXING` from `Map<Long, …>` | primitive open-addressed arrays — no wrapper keys, so nothing to box |
| `NEW_ARRAY` per call | one `scratch` buffer, allocated at construction and reused |
| `STRING_CONCAT` | no string building on the hot path at all |

`process()` also shows the transitive walk: it calls `normalise()`, which is followed and found
clean. Had the helper allocated, the finding would name the helper and show the path that reached
it.

To see the failure mode, add `return new Object();` to any method here and re-run — the build
breaks, which is the entire point of wiring it into `check`.
