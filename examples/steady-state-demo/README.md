# Steady-state demo

Two hot paths that are statically indistinguishable and behave completely differently under load.

- `PricingEngine` — lazily allocates a buffer. Guarded, cached, and it really does warm up.
- `ResizingCache` — lazily allocates a buffer that keeps being outgrown. Guarded, cached, and it
  never stops allocating.

Both pass the static checker with **zero findings**. Only the runtime flight recorder tells them
apart.

## Run it

```bash
../../gradlew -p ../.. :core:shadowJar

AGENT=../../core/build/libs/core-0.1.0-SNAPSHOT-agent.jar
mkdir -p build/classes
javac -cp $AGENT -d build/classes $(find src -name '*.java')
java -javaagent:$AGENT -cp build/classes:$AGENT demo.LoadDriver
```

## What you should see

```
round    operations   recorded      new   sites
1           2000000         36       36   PricingEngine#levels=4  ResizingCache#scratch=32
2           4000000        127       91   PricingEngine#levels=4  ResizingCache#scratch=123
...
8          16000000        204        4   PricingEngine#levels=4  ResizingCache#scratch=200
```

`PricingEngine#levels` stops at 4 after round 1 and never moves again, through 16 million
operations. `ResizingCache#scratch` is still climbing in round 8.

(The count of 4 rather than 1 is the four load threads racing through the unsynchronised lazy init —
another thing the static checker cannot see.)

Exact numbers vary per run; the shape does not.

Without `-javaagent` nothing is instrumented and every count is zero.

The full walkthrough is in the [documentation](https://rob-langham.github.io/jvm-bytecode-performance-checks/runtime/steady-state/).
