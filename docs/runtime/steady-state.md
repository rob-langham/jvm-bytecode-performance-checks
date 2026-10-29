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

That is what the runtime trace is for.

## How it works

The agent records every allocation, and the report decides what it means afterwards.

1. **The agent writes a trace.** Each allocation at a warmup site appends a fixed 12-byte record to
   `allocations.bin`: a 4-byte site code, then an 8-byte timestamp in nanoseconds.
2. **A key file says what each code is.** `allocations.keys` has one `code<TAB>site` line per site,
   where the site is `class#method:line@offset:CATEGORY`. The key names the allocation in the
   bytecode, so the same site always has the same key.
3. **The report reads both files.** It turns the trace into counts and times, then reads the classes
   for their annotations and runs the static checker over them.

Attach it at startup:

```bash
./gradlew :core:shadowJar
java -javaagent:core/build/libs/core-0.1.0-agent.jar=dir=build/allocation-trace -jar your-app.jar
java -cp core/build/libs/core-0.1.0.jar com.staticallocationchecker.trace.TraceReport \
     build/allocation-trace target/classes
```

The `dir=` option is where the trace goes. It defaults to `allocation-trace` in the working
directory. The trace is complete when the JVM exits normally.

Your source is untouched. At class load the agent inserts a single `LDC code; INVOKESTATIC recordSite`
before each allocation. In `PricingEngine#levels` the effect is as if the method had been written:

```java
@AllocationsForWarmup
long[] levels() {
    if (levels == null) {
        AllocationTrace.recordSite(0);   // inserted by the agent; 0 is the code for this site
        levels = new long[64];
    }
    return levels;
}
```

The probe is an integer constant and a static call. Nothing is analysed while the program runs, which
keeps the added work small. Whether any remaining cost changes what the JIT compiles is something to
measure on your own workload, not something this page claims.

## The demo

`examples/steady-state-demo/` contains a runnable version of the comparison above:

| File | What it is |
| --- | --- |
| `PricingEngine.java` | A hot path whose buffer warms up once and stays warm |
| `ResizingCache.java` | A hot path whose buffer keeps being outgrown |
| `LoadDriver.java` | Four threads, eight rounds of two million operations each |

Run it:

```bash
./gradlew :core:shadowJar

cd examples/steady-state-demo
AGENT=../../core/build/libs/core-0.1.0-SNAPSHOT-agent.jar
mkdir -p build/classes
javac -cp $AGENT -d build/classes $(find src -name '*.java')
java -javaagent:$AGENT=dir=build/trace -cp build/classes:$AGENT demo.LoadDriver
java -cp $AGENT com.staticallocationchecker.trace.TraceReport build/trace build/classes
```

## First: both classes pass the static check

```
$ # analysing examples/steady-state-demo/build/classes
TOTAL 0
```

No findings. Statically, these two classes are equally well-behaved.

## Then: the trace says which one settles

This is real output from one run of the demo:

```
Allocation trace: examples/steady-state-demo/build/trace
Events: 171 over 2863.571 ms, from 2 site(s)
Steady: no allocations in the last 20% of the run.

code       count     first ms      last ms  state              site
0              2        0.000        0.002  steady             demo.PricingEngine#levels
1            169        0.187     2863.571  STILL ALLOCATING   demo.ResizingCache#scratch

Sites:
  0  demo.PricingEngine#levels:26@9:NEW_ARRAY
      contract: @AllocationsForWarmup
      static findings on this method: 0
  1  demo.ResizingCache#scratch:26@15:NEW_ARRAY
      contract: @AllocationsForWarmup
      static findings on this method: 0
```

**`PricingEngine#levels` settles immediately.** Its two allocations both happen at the start, and
then nothing for the rest of the run. That is warmup working as the annotation claims.

**`ResizingCache#scratch` never does.** It is still allocating in the final moments of the run. On a
latency-sensitive path this is a slow leak of GC pressure that no amount of code review or static
checking would have found.

Both sites are governed by `@AllocationsForWarmup` in the bytecode, and the checker has no findings
against either. The difference is in the behaviour, and only the trace shows it.

## Why `levels` fires twice and not once

Two threads can both observe `levels == null` before either writes it, so both allocate. Each thread
ends up with a valid 64-long array and the last write wins, which is benign. But it does more work
than the code claims.

The static checker cannot see this: the shape is compliant, and it has no model of threads. A site
that fires a handful of times at the start, matching your thread count, is the tell for an
unsynchronised lazy init.

## Reading the trace

| Pattern | Means |
| --- | --- |
| Count rises early, then the site is `steady` | Warmed up. Working as intended. |
| `STILL ALLOCATING` at the end of the run | Not a warmup site. The guard depends on runtime values. |
| A few events at the start, matching your thread count | Racy lazy init. Benign or not, it is doing N× the work. |
| A site you expected is missing from the report | Either that path never ran, or the class was loaded before the agent arrived. |
| A site is `STILL ALLOCATING` but is cheap per event | Worth knowing even if the rate is low; a steady trickle still builds up. |

"Steady" means the site made no allocations in the final 20% of the run. That cut-off is a
judgement, so read the last-ms column too: a site that stopped at 80% of the run is steady, but a
site that stopped at 79% may not have been given long enough to show it.

## Doing this in your own load test

**1. Attach at startup.** `premain` instruments at class-load time, which covers everything because
nothing of the application is loaded that early. Attaching to a running JVM also works: `agentmain`
retransforms the classes already loaded that declare `@AllocationsForWarmup`. Two limits there:
**bootstrap-loaded classes are skipped**, and any allocation that happened before the attach is not
in the trace. For measuring warmup, starting with `-javaagent` is the more honest measurement.

**2. Drive real load until throughput settles, then look at the tail.** Class loading, JIT
compilation, connection pools and caches all legitimately allocate early. The steady question is
whether a site is still allocating near the end of a long run, so run long enough for the early
noise to pass.

**3. Read the report in CI, not just by eye.** The report is plain text, so a check can fail the
build when a site you expect to settle reports `STILL ALLOCATING`. The demo does exactly this in its
`verifyRun` task.

**4. Use the static checker for the rest.** Anything the trace cannot reach, because it never ran,
is still covered by the static check. The two are complementary, not alternatives.

**5. Reach for it whenever a warmup method has a loop or a size-dependent guard.** Those are the two
shapes where [the static contract is weakest](../scenarios/warmup-caching.md#a-loop): both pass, and
both can allocate unboundedly.

## Limits to be aware of

- **Attaching late skips bootstrap-loaded classes**, and anything that allocated before the attach is
  not in the trace.
- **A killed JVM loses its buffered events.** The trace is written in chunks per thread and flushed
  on normal exit. A process that is killed rather than shut down may lose the last few thousand
  events.
- **The trace only sees `@AllocationsForWarmup` methods.** It is not a general allocation profiler —
  for that, use JFR's `ObjectAllocationSample` or async-profiler. This tool answers one narrow
  question: did the sites I declared as warmup actually stop firing?
- **The report reads classes from directories.** Point it at compiled class directories, not at
  jars, so it can read the annotations.

## Use both, for different questions

| Question | Tool | Where it runs |
| --- | --- | --- |
| Can this path allocate at all? | Static checker | CI, every build |
| Is this warmup allocation guarded and cached? | Static checker | CI, every build |
| Does this warmup site stop firing? | Trace and report | Load-test environment |
| Is that lazy init racing? | Trace and report | Load-test environment |
| What else is allocating? | JFR / async-profiler | Anywhere |

The static checker is cheap, exhaustive over the code it can see, and proves shapes. The trace is
narrow, needs a realistic workload, and proves behaviour. Neither substitutes for the other, and the
`ResizingCache` above is the reason why.
