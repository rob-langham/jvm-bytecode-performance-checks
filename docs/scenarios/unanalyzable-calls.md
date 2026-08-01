---
title: Unanalyzable calls
parent: Allocation Scenarios
nav_order: 12
---

# Unanalyzable calls

**"I could not check this" — not "this allocates".**

This is not an allocation finding. It is the checker declining to guess, and on a first run it will
probably be most of what you see.

## Why it exists

```java
@ZeroAllocations
public int entry(String s) {
    return s.length();
}
```

`String.length()` allocates nothing, and you know that. The checker does not, because
`java/lang/String` is not among the class files it was given. It has never read that method.

It has two options: assume the call is fine, or say it could not check. It says it could not check:

| Field | Value |
| --- | --- |
| `kind` | `UNANALYZABLE_CALL` |
| `className` | `com.staticallocationchecker.fixtures.UnresolvableCall` |
| `methodName` | `entry` |
| `line` | `10` |
| `category` | `null` — there is no allocation to categorise |
| `callPath` | `UnresolvableCall#entry(String)` → **`java.lang.String#length()`** |

The last element of the call path is the call it could not follow, so you always know exactly what
it was.

The alternative is untenable for a verification tool. A `@ZeroAllocations` method whose entire body
is one call into an unindexed library would pass silently, and the report would say "clean" about
code that was never read.

## The three ways to get one

**1. The callee is not in the analysis roots.** JDK calls, third-party libraries, another module
the plugin was not pointed at. This is the common case by a wide margin.

**2. An interface whose implementations are not in the roots.** `values.size()` on a
`java.util.List` — nothing to walk, so nothing is assumed. See
[virtual dispatch](virtual-dispatch.md#when-it-cannot-see-any-implementation).

**3. A warmup method the analysis could not process.** The same kind is reused when the dataflow
analysis of an `@AllocationsForWarmup` method fails, so an unanalysable method is never silently
mistaken for a compliant one. You can tell these apart: `line` is `-1` and `callPath` has exactly
one element, with no unresolved target on the end.

## Making them go away

**Give the checker more roots.** This is the real fix. Both plugins now take configuration:

```kotlin
// Gradle
tasks.named<StaticAllocationCheckerTask>("checkStaticAllocation") {
    classesDirs.from(project(":shared-domain").layout.buildDirectory.dir("classes/java/main"))
}
```

```xml
<!-- Maven -->
<configuration>
  <additionalRoots>
    <additionalRoot>${project.basedir}/../shared-domain/target/classes</additionalRoot>
  </additionalRoots>
</configuration>
```

Anything reachable that you can point it at will resolve, and the finding disappears — replaced,
possibly, by a real allocation finding from inside that code, which is the point.

**Do not try to index the JDK.** Calls into `java.*` will always be unanalyzable in practice. The
realistic goal is a hot path that does not call into it — which, on genuinely allocation-free code,
is roughly where you want to be anyway.

**Adopt gradually.** Both plugins support reporting findings without failing the build, which is how
you turn this on over an existing codebase:

```kotlin
tasks.named<StaticAllocationCheckerTask>("checkStaticAllocation") {
    ignoreFailures.set(true)
}
```

```xml
<configuration>
  <ignoreFailures>true</ignoreFailures>
</configuration>
```

## How to read one

As **unverified**, not broken. It is a gap in coverage, and the size of the gap is a function of how
much of your dependency surface you were able to supply.

A useful habit: treat a rising `UNANALYZABLE_CALL` count as a signal that the hot path has grown a
new dependency. That is often worth knowing on its own, regardless of whether the callee allocates.

{: .note }
> `resolveClasspath` — roots used only to *resolve* callees, never scanned for annotated entry
> points — is accepted by both plugins and passed through to the analyser, which does not yet use it
> to widen resolution. Until it does, widening coverage means adding analysis roots, which also
> makes any annotated methods inside them into entry points of their own.
