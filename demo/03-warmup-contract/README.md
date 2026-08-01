# 03 — The warmup contract

Zero-allocation code still has to allocate its buffers once. `@AllocationsForWarmup` permits that
under a contract: each allocation must be **guarded** (some path skips it) and **cached** (the
reference is retained). That is the lazy-init shape, and nothing else.

```bash
./gradlew -p demo :03-warmup-contract:checkStaticAllocation
```

## Expected: 2 findings

| Method | Finding | Why |
| --- | --- | --- |
| `unguarded` | `WARMUP_NOT_GUARDED` | Allocates on every call — it never stops warming up |
| `uncached` | `WARMUP_NOT_CACHED` | Guarded, but the result is discarded and re-made later |

## Compliant, and reported clean

- `buffer()` — guarded by the null check, stored into a field. The canonical shape.
- `prefill()` — retained by adding to a field-held collection. Object pools are a primary use case,
  so "cached" means more than a direct field store.
- `hotPath()` — a `@ZeroAllocations` method calling a warmup method. The walk stops at the boundary
  and treats the sanctioned allocations as allowed.

## The limit worth knowing

This proves the allocation *can* only happen on a guarded path. It cannot prove it only happens
once — a guard that keeps letting allocations through still satisfies the contract. That is what
scenario 07 is for.
