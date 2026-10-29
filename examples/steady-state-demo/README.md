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

# 1. Run the load. The agent writes every allocation to build/trace.
java -javaagent:$AGENT=dir=build/trace -cp build/classes:$AGENT demo.LoadDriver

# 2. Report on the trace, reading the classes for their annotations.
java -cp $AGENT com.staticallocationchecker.trace.TraceReport build/trace build/classes
```

## What you should see

```
By contract:
  warmup          sites    2  events        130  still allocating 1  violations 0
  unannotated     sites   12  events        133  still allocating 0  violations 0

code       count     first ms      last ms  contract        state              site
12             1        5.500        5.500  warmup          steady             demo.PricingEngine#levels
13           129        5.577     2593.068  warmup          STILL ALLOCATING   demo.ResizingCache#scratch
```

`PricingEngine#levels` fires at the very start and never again, through 16 million operations.
`ResizingCache#scratch` is still allocating near the end of the run. The other twelve sites are the
driver's own allocations: counted, not judged, because nothing annotates them.

The count for `levels` is a little more than 1 because the four load threads race through the
unsynchronised lazy init — another thing the static checker cannot see.

Exact numbers vary per run; the shape does not. Without `-javaagent` nothing is instrumented and no
trace is written.

The full walkthrough is in the [documentation](https://rob-langham.github.io/jvm-bytecode-performance-checks/runtime/steady-state/).
