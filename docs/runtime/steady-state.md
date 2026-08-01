---
title: Warmup Under Load
nav_order: 5
---

# Warmup under load

{: .no_toc }

1. TOC
{:toc}

---

## The question static analysis cannot answer

The [warmup contract](../scenarios/warmup-contract.md) proves a shape: the allocation is guarded by
a branch, and the reference is cached into a field. That is a statement about the control-flow
graph, and it is all a static analyser can honestly claim.

What it does not prove is that the guard eventually stops being taken. Compare these two methods —
**both pass the static check with zero findings**:

```java
private long[] levels;

@AllocationsForWarmup
long[] levels() {
    if (levels == null) {
        levels = new long[64];       // fires once per instance, forever
    }
    return levels;
}
```

```java
private byte[] scratch;

@AllocationsForWarmup
byte[] scratch(int size) {
    if (scratch == null || scratch.length < size) {
        scratch = new byte[size];    // fires whenever a request outgrows the buffer
    }
    return scratch;
}
```

The second one is guarded and cached and reallocates forever under a workload whose request sizes
keep growing. Whether it settles depends on the *values* it is called with, which no amount of
bytecode analysis will tell you.

That is what the runtime flight recorder is for.

## How the agent works

Attach it at startup:

```bash
./gradlew :core:shadowJar
java -javaagent:core/build/libs/core-0.1.0-SNAPSHOT-agent.jar -jar your-app.jar
```

At class-load time, the agent rewrites every `@AllocationsForWarmup` method — and the synthetic
methods behind any lambdas they create — inserting a counter immediately before each allocation. In
`PricingEngine#levels`, the effect is as if the method had been written:

```java
@AllocationsForWarmup
long[] levels() {
    if (levels == null) {
        record("demo.PricingEngine#levels:26@9:NEW_ARRAY");   // inserted by the agent
        levels = new long[64];
    }
    return levels;
}
```

Your source is untouched; the rewriting happens to the compiled class as it is loaded. Two things
about the site key are worth knowing:

- **It identifies a site, not a line.** `class#method:line@offset:category` includes a position
  within the method, because class, method, line and category together do not identify an
  allocation: two allocations of the same kind can share a source line, and when a class is compiled
  without debug information *every* site in a method reports the same line. Collapsing those would
  make "one site fired twice" indistinguishable from "two sites fired once each" — the exact
  distinction the recorder exists to draw.
- **It is stable for a given compiled class**, so counts can be compared between runs and between
  the recorder and the static checker.

The agent also registers an MXBean at
`com.staticallocationchecker:type=AllocationFlightRecorder`, exposing `TotalAllocations`, a
per-site map of `count`/`firstSeenMillis`/`lastSeenMillis`, and `reset()`.

## The demo

`examples/steady-state-demo/` contains a runnable version of the comparison above:

| File | What it is |
| --- | --- |
| `PricingEngine.java` | A hot path whose buffer warms up once and stays warm |
| `ResizingCache.java` | A hot path whose buffer keeps being outgrown |
| `LoadDriver.java` | Four threads, eight rounds of two million operations, sampling the recorder between rounds |

Run it:

```bash
./gradlew :core:shadowJar

cd examples/steady-state-demo
AGENT=../../core/build/libs/core-0.1.0-SNAPSHOT-agent.jar
mkdir -p build/classes
javac -cp $AGENT -d build/classes $(find src -name '*.java')
java -javaagent:$AGENT -cp build/classes:$AGENT demo.LoadDriver
```

## First: both classes pass the static check

```
$ # analysing examples/steady-state-demo/build/classes
TOTAL 0
```

No findings. Statically, these two classes are equally well-behaved.

## Then: run them under load

```
round    operations   recorded      new   sites
1           2000000         36       36   PricingEngine#levels=4  ResizingCache#scratch=32
2           4000000        127       91   PricingEngine#levels=4  ResizingCache#scratch=123
3           6000000        154       27   PricingEngine#levels=4  ResizingCache#scratch=150
4           8000000        162        8   PricingEngine#levels=4  ResizingCache#scratch=158
5          10000000        169        7   PricingEngine#levels=4  ResizingCache#scratch=165
6          12000000        175        6   PricingEngine#levels=4  ResizingCache#scratch=171
7          14000000        200       25   PricingEngine#levels=4  ResizingCache#scratch=196
8          16000000        204        4   PricingEngine#levels=4  ResizingCache#scratch=200
```

That is real output, not an illustration.

**`PricingEngine#levels` reaches steady state immediately.** It fires 4 times during round 1 and
then never again — through 16 million operations. That is warmup working exactly as the annotation
claims.

**`ResizingCache#scratch` never does.** It is still allocating in round 8, after 16 million
operations, and the `new` column never reaches zero. The allocation rate falls (the buffer does keep
getting bigger, so it is outgrown less often) but it never stops. On a latency-sensitive path this
is a slow leak of GC pressure that no amount of code review or static checking would have found.

## Why `levels` is 4 and not 1

This is the second thing the recorder tells you, and it is worth dwelling on.

```java
@AllocationsForWarmup
long[] levels() {
    if (levels == null) {
        levels = new long[64];
    }
    return levels;
}
```

Four threads start simultaneously, all observe `levels == null`, and all four allocate. Three of
those arrays are immediately garbage. The code is *benignly* racy — every thread ends up with a
valid 64-long array, and the last write wins — but it does four times the work it claims to.

The static checker cannot see this: the shape is compliant, and it has no model of threads. The
count of 4 against a thread pool of 4 is the tell. If you see a warmup site whose count matches your
thread count, you have found an unsynchronised lazy init.

## Reading the numbers

| Pattern | Means |
| --- | --- |
| Count rises, then `new` is 0 for the rest of the run | Warmed up. Working as intended. |
| `new` never reaches 0 | Not a warmup site. The guard depends on runtime values. |
| Count ≈ thread count, then flat | Racy lazy init. Benign or not, it is doing N× the work. |
| Count is 0 for a site you expected | Either that path never ran, or the class was loaded before you attached. |
| `lastSeenMillis` long after startup | The site fired late. Worth knowing even if the count is low. |

The `new` column is the one to watch. Total counts are cumulative and always go up; what matters is
whether they *stop* going up while load continues.

## Doing this in your own load test

**1. Attach at startup where you can; attaching later also works.** `premain` instruments at
class-load time, which covers everything because nothing of the application is loaded that early.
`agentmain` — the attach-API path — additionally sweeps `getAllLoadedClasses()` and retransforms
those declaring `@AllocationsForWarmup`, so attaching to a running JVM does see the classes that
were already loaded.

Two limits on the attach path worth knowing: **bootstrap-loaded classes are skipped**, because they
cannot see the recorder the injected probe calls, and any allocation that already happened before
you attached is, of course, not counted. For measuring warmup specifically, starting with
`-javaagent` remains the more honest measurement.

**2. Get past your real warmup first, then `reset()`.** Class loading, JIT compilation, connection
pools and caches all legitimately allocate early. Drive load until throughput stabilises, call
`reset()` on the MXBean, then drive load again. Anything recorded in the second window fired in
steady state, which is a much stronger signal than a raw cumulative count.

**3. Sample, do not just read the end.** A single final total tells you a site fired 200 times but
not whether that was 200 times in the first second or steadily throughout. Sample periodically and
watch the delta — that is what the `new` column in the demo is.

**4. Alert on the delta in steady state.** Once the process is warm, the delta for every site should
be 0. A non-zero delta is a regression, and it is a much cheaper signal to collect than a GC-log
analysis:

```java
static final SiteRecord NEVER_FIRED = new SiteRecord(0, 0, 0);

Map<String, SiteRecord> before = recorder.snapshot();
// … one minute of steady-state load …
Map<String, SiteRecord> after = recorder.snapshot();

after.forEach((site, record) -> {
    long delta = record.count() - before.getOrDefault(site, NEVER_FIRED).count();
    if (delta > 0) {
        throw new AssertionError(site + " allocated " + delta + " times in steady state");
    }
});
```

That is a load-test assertion, not a monitoring dashboard. It belongs in the same CI pipeline as the
static check — just in the stage that has a workload attached.

**5. Reach for it whenever a warmup method has a loop or a size-dependent guard.** Those are the two
shapes where [the static contract is weakest](../scenarios/warmup-caching.md#a-loop): both pass, and
both can allocate unboundedly.

## Limits to be aware of

- **On dynamic attach, bootstrap-loaded classes are skipped** — they cannot see the recorder the
  injected probe calls — and anything that allocated before you attached was never counted.
- **The recorder only sees `@AllocationsForWarmup` methods.** It is not a general allocation
  profiler — for that, use JFR's `ObjectAllocationSample` or async-profiler. This tool answers one
  narrow question: did the sites I declared as warmup actually stop firing?

## Use both, for different questions

| Question | Tool | Where it runs |
| --- | --- | --- |
| Can this path allocate at all? | Static checker | CI, every build |
| Is this warmup allocation guarded and cached? | Static checker | CI, every build |
| Does this warmup site stop firing? | Flight recorder | Load-test environment |
| Is that lazy init racing? | Flight recorder | Load-test environment |
| What else is allocating? | JFR / async-profiler | Anywhere |

The static checker is cheap, exhaustive over the code it can see, and proves shapes. The recorder is
narrow, needs a realistic workload, and proves behaviour. Neither substitutes for the other, and the
`ResizingCache` above is the reason why.
