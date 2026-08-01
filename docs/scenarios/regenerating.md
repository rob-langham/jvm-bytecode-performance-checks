---
title: Regenerating these pages
parent: Allocation Scenarios
nav_order: 16
---

# Regenerating these pages

Every Java snippet and every finding on the scenario pages was taken from a real run
against the fixtures in `core/src/test/java/com/staticallocationchecker/fixtures/`. When the
analyser's behaviour changes, this is how to check the pages still tell the truth.

## Build the fixtures and the agent jar

```bash
./gradlew :core:compileTestJava :core:shadowJar
```

That gives you two things:

- `core/build/classes/java/test/com/staticallocationchecker/fixtures/` — the compiled fixtures.
- `core/build/libs/core-0.1.0-SNAPSHOT-agent.jar` — a self-contained jar with the analyser and a
  relocated copy of ASM, which makes it a convenient one-entry classpath.

## Regenerate the findings

`docs/tools/FindingsReport.java` prints the readable form of every finding, grouped by the class
containing the site. It is a single-file source program, so it needs no separate compilation step:

```bash
java -cp core/build/libs/core-0.1.0-SNAPSHOT-agent.jar \
     docs/tools/FindingsReport.java \
     core/build/classes/java/test/com/staticallocationchecker/fixtures
```

Output:

```
=== com.staticallocationchecker.fixtures.Autoboxing
  ZERO_ALLOCATION_VIOLATION  BOXING  box(I)Ljava/lang/Object;:10
      path: com.staticallocationchecker.fixtures.Autoboxing#box(I)Ljava/lang/Object;

…

TOTAL 34
```

That total is the number quoted across the scenario pages. If it changes, something in the
analyser's behaviour changed with it, and the affected page needs revisiting.

The same tool works on any analysis roots, which is the quickest way to run the checker against your
own classes without wiring up a plugin:

```bash
java -cp core/build/libs/core-0.1.0-SNAPSHOT-agent.jar \
     docs/tools/FindingsReport.java build/classes/java/main libs/shared-domain.jar
```

## Regenerate the load-test output

```bash
./gradlew :core:shadowJar

cd examples/steady-state-demo
AGENT=../../core/build/libs/core-0.1.0-SNAPSHOT-agent.jar
mkdir -p build/classes
javac -cp $AGENT -d build/classes $(find src -name '*.java')
java -javaagent:$AGENT -cp build/classes:$AGENT demo.LoadDriver
```

The exact counts vary between runs — thread scheduling decides how many threads race through the
unsynchronised lazy init, and how quickly the resizing buffer is outgrown. The *shape* is stable and
is what the page is about: one site flat after round 1, the other still climbing at round 8.

## Cross-check against the test suite

The fixtures are not documentation props — they are the corpus the test suite asserts against, in
`AllocationsTest`, `ResolutionTest`, `WarmupContractTest`, `AnnotationSemanticsTest` and others. If
a scenario page and a test disagree, the test is right.

```bash
./gradlew build     # unit tests plus :core:agentTest, which launches a real JVM with -javaagent
```

The suite once carried a set of `@Disabled` tests, each stating behaviour the tool should have and
naming the gap it was waiting on. All of them are now enabled and passing — so if a page here
describes a limitation, check [Known gaps](../usage.md#known-gaps) before believing it.
