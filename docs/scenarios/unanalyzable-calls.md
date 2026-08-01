---
title: Unanalyzable calls
parent: Allocation Scenarios
nav_order: 12
---

# Unanalyzable calls

Not an allocation — a refusal to guess. This will be most of your findings on a first run, so it is
worth understanding exactly what it means.

## The Java

`core/src/test/java/com/staticallocationchecker/fixtures/UnresolvableCall.java`

```java
public class UnresolvableCall {

    @ZeroAllocations
    public int entry(String s) {
        return s.length();
    }
}
```

`String.length()` allocates nothing, and everyone knows it. The checker does not, because
`java/lang/String` is not in the analysis roots.

## The bytecode

```
  public int entry(java.lang.String);
    Code:
       0: aload_1
       1: invokevirtual // Method java/lang/String.length:()I
       4: ireturn
```

## What the checker reports

| Field | Value |
| --- | --- |
| `kind` | `UNANALYZABLE_CALL` |
| `className` | `com.staticallocationchecker.fixtures.UnresolvableCall` |
| `methodName` / `methodDescriptor` | `entry` `(Ljava/lang/String;)I` |
| `line` | `10` |
| `category` | **`null`** — there is no allocation to categorise |
| `callPath` | `…UnresolvableCall#entry(Ljava/lang/String;)I` → `java.lang.String#length()I` |

The last element of `callPath` is the *unresolved target*, appended by `targetSignature(call)`, so
the finding names exactly which call could not be followed.

## Why

When resolution returns nothing, the walk reports rather than continuing:

```java
List<ClassHierarchy.MethodRef> targets =
        hierarchy.resolve(call.getOpcode(), call.owner, call.name, call.desc);
if (targets.isEmpty()) {
    List<String> unresolvedPath = new ArrayList<>(callPath);
    unresolvedPath.add(targetSignature(call));
    findings.add(new Finding(Finding.Kind.UNANALYZABLE_CALL, className, method.name,
            method.desc, line, null, unresolvedPath));
    continue;
}
```

The alternative — treating an unresolvable call as clean — would mean a `@ZeroAllocations` method
whose entire body is a call into an unindexed library passes silently. For a tool whose output is
"this path does not allocate", that is the one failure mode that must not happen.

## The three ways to get one

**1. The callee is outside the analysis roots.** JDK calls, third-party libraries, anything in
another module the plugin did not pass. This is the common case.

**2. An interface with no indexed implementations.** From the
[`Dispatch` fixture](virtual-dispatch.md):

```java
@ZeroAllocations
public int throughUnindexedInterface(java.util.List<String> values) {
    return values.size();
}
```

`ClassHierarchy` finds `java/util/List` is not indexed, so there is no declaration and no override
to walk. Note the failure mode this prevents: if `List` *were* indexed but no implementation was,
`hasBody` would reject the abstract declaration and resolution would still be empty — the checker
never mistakes an abstract declaration for a body that allocates nothing.

**3. A warmup method whose dataflow analysis failed.** The same kind is reused for a different
situation, in `analyzeWarmupMethod`:

```java
} catch (AnalyzerException e) {
    // Returning quietly would make an unanalysable warmup method indistinguishable from a
    // compliant one. Say so instead, so the gap in coverage is visible in the report.
    findings.add(new Finding(Finding.Kind.UNANALYZABLE_CALL, …, -1, null, …));
    return;
}
```

You can tell these apart: a dataflow failure has `line = -1` and a `callPath` of exactly one
element — the warmup method itself, with no unresolved target appended.

## Living with it

The full guidance is in [Usage](../usage.md#dealing-with-unanalyzable-calls). In short:

- **Add roots.** `analyze` takes a list of directories and jars; give it the modules and libraries
  your hot path actually calls into.
- **Do not try to index the JDK.** Aim instead for a hot path that does not call into it.
- **Read it as "unverified", not "broken".**

{: .warning }
> The build plugins each pass exactly one root — `build/classes/java/main` for Gradle,
> `${project.build.outputDirectory}` for Maven — and take no configuration. Anything multi-module
> needs the library API today. This is the single biggest practical limitation of the tool.

## The one that is not reported

`resolveClasspath`, the second parameter of `analyze`, exists for exactly this problem: a classpath
used only to *resolve* callees, without treating its classes as entry points to scan for
annotations. It is accepted and currently ignored. Passing your dependencies there does not yet
reduce the finding count — you have to pass them as analysis roots, which also makes their own
annotated methods entry points.
