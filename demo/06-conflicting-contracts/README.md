# 06 — Conflicting contracts

The two annotations contradict each other: one forbids allocation, the other permits it under
conditions. Claiming both on one declaration is a mistake.

```bash
./gradlew -p demo :06-conflicting-contracts:checkStaticAllocation
```

## Expected: 1 finding

`confused()` → `CONFLICTING_CONTRACTS`.

It is **reported rather than resolved by precedence**. Silently applying one would mean the looser
contract suppressing the stricter one — a green result for something never really checked, which is
the failure mode this whole tool exists to prevent. Telling the author is more useful than choosing
for them.

## What is *not* a conflict

`PriceLevels` carries `@ZeroAllocations` on the type and `@AllocationsForWarmup` on one method.
That is a normal and useful thing to write: the type sets a default, the method is a deliberate,
more specific exception. The nearer declaration wins, and nothing is reported.

Flagging that shape would make the type-level annotation unusable on any real class — which is why
the conflict check is deliberately scoped to a single declaration.
