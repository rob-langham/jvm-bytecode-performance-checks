# 07 — The runtime trace

Two classes the static checker **cannot** tell apart. Both satisfy the warmup contract exactly:
guarded by a check, cached into a field. Both report zero findings.

One of them never stops allocating.

```bash
./gradlew -p demo :07-runtime-flight-recorder:verifyRun
```

`verifyRun` runs the load, then reports on the trace it wrote and fails if the result does not
show what this README claims.

## Expected output

The first 0.1 seconds are warm-up. Abridged from one run:

```
By contract:
  warmup          sites    2  warm-up events        529  steady events       2472  still allocating 1  violations 0
  unannotated     sites    5  warm-up events          5  steady events         15  still allocating 0  violations 0

code       count    warm-up     steady     first ms      last ms  contract        state             site
5              1          1          0        5.549        5.549  warmup          steady            demo.runtime.PricingEngine#levels
6           3000        528       2472        5.571      181.804  warmup          STILL ALLOCATING  demo.runtime.ResizingCache#scratch
```

`PricingEngine#levels` allocates once, during warm-up, and never again through thirty million
operations. `ResizingCache#scratch` is still allocating well after warm-up.

## Why static analysis cannot catch this

`ResizingCache` guards its allocation and caches the result — it satisfies the contract to the
letter. The bug is that the guard *keeps letting allocations through*, because the workload's
high-water mark creeps up. No amount of bytecode analysis can see that: it depends on the data.

Static analysis proves an allocation *can* only happen on a guarded path. Only the trace shows
whether it *did* stop happening. A warmup site that is still allocating at the end of a run is a
bug the checker structurally cannot find.

## How the agent is wired

The agent records every allocation site in the application at class-load time. Each allocation
writes a 12-byte record to `allocations.bin` in the trace directory: a site code, then a nanosecond
timestamp. `allocations.keys` maps each code to the site it stands for. The agent does no analysis;
it stays out of the way of the JIT.

The report reads both files, and reads this build's classes for the `@AllocationsForWarmup` and
`@ZeroAllocations` annotations. Those decide what each site means: the warmup site is expected to
settle, and an allocation under `@ZeroAllocations` would be a violation.

It is a **separate, shaded artifact** from the library jar. A `-javaagent` jar is appended to the
system class path with nothing beside it, so it must carry its own dependencies — with ASM
relocated, so it cannot collide with whatever version the host application already uses.

The build file here does that for you:

```kotlin
tasks.named<JavaExec>("run") {
    dependsOn(agentJar)                                    // builds :core:shadowJar
    jvmArgs("-javaagent:${agentJarFile()}=dir=$traceDirectory,warmup=0.1")
}
```

In a real project, with the artifact published, that is just:

```
java -javaagent:core-<version>-agent.jar=dir=build/allocation-trace,warmup=30 -jar your-app.jar
java -cp core-<version>.jar com.staticallocationchecker.trace.TraceReport build/allocation-trace build/classes
```

## Related

[`examples/steady-state-demo`](../../examples/steady-state-demo) shows the same idea with `javac`
and raw `java`, and with four threads racing through the lazy init — another thing the static
checker cannot see.
