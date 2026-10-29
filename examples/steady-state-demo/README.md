# Steady-state demo

Two hot paths that are statically indistinguishable and behave completely differently under load.

- `PricingEngine` — lazily allocates a buffer. Guarded, cached, and it really does warm up.
- `ResizingCache` — lazily allocates a buffer that keeps being outgrown. Guarded, cached, and it
  never stops allocating.

Both pass the static checker with **zero findings**. Only the runtime trace tells them apart.

## Run it

```bash
../../gradlew -p ../.. :core:shadowJar

AGENT=../../core/build/libs/core-0.1.0-SNAPSHOT-agent.jar
mkdir -p build/classes
javac -cp $AGENT -d build/classes $(find src -name '*.java')

# 1. Run the load. The agent writes every allocation to build/trace, and treats the first 0.5 s as warm-up.
java -javaagent:$AGENT=dir=build/trace,warmup=0.5 -cp build/classes:$AGENT demo.LoadDriver

# 2. Report on the trace, reading the classes for their annotations.
java -cp $AGENT com.staticallocationchecker.trace.TraceReport build/trace build/classes
```

## What you should see

Abridged, from one run:

```
Warm-up: ends 442.409 ms into the run. Only allocations after that are steady state.

By contract:
  warmup          sites    2  warm-up events         49  steady events         64  still allocating 1  violations 0
  unannotated     sites   12  warm-up events         33  steady events        100  still allocating 0  violations 0

code       count    warm-up     steady     first ms      last ms  contract        state             site
12             1          1          0        4.949        4.949  warmup          steady            demo.PricingEngine#levels
13           112         48         64        5.013     2902.758  warmup          STILL ALLOCATING  demo.ResizingCache#scratch
```

`PricingEngine#levels` allocates during warm-up and never again. `ResizingCache#scratch` is still
allocating after warm-up. The other twelve sites are the driver's own allocations: counted, not judged,
because nothing annotates them.

The count for `levels` can be more than 1 because the four load threads race through the
unsynchronised lazy init, which is also during warm-up. That is another thing the static checker
cannot see.

Exact numbers vary per run; the shape does not. Without `-javaagent` nothing is instrumented and no
trace is written.

The full walkthrough is in the [documentation](https://rob-langham.github.io/jvm-bytecode-performance-checks/runtime/steady-state/).
