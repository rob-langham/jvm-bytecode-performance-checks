# 07 — The runtime flight recorder

Two classes the static checker **cannot** tell apart. Both satisfy the warmup contract exactly:
guarded by a check, cached into a field. Both report zero findings.

One of them never stops allocating.

```bash
./gradlew -p demo :07-runtime-flight-recorder:run
```

## Expected output

```
round    operations     recorded     per-site counts
1        500000         51           PricingEngine#levels=1  ResizingCache#scratch=50
2        1000000        101          PricingEngine#levels=1  ResizingCache#scratch=100
3        1500000        151          PricingEngine#levels=1  ResizingCache#scratch=150
4        2000000        201          PricingEngine#levels=1  ResizingCache#scratch=200
5        2500000        251          PricingEngine#levels=1  ResizingCache#scratch=250
6        3000000        301          PricingEngine#levels=1  ResizingCache#scratch=300
```

`PricingEngine#levels` fires **once** and never again, through three million operations.
`ResizingCache#scratch` is still climbing linearly in round six.

## Why static analysis cannot catch this

`ResizingCache` guards its allocation and caches the result — it satisfies the contract to the
letter. The bug is that the guard *keeps letting allocations through*, because the workload's
high-water mark creeps up. No amount of bytecode analysis can see that: it depends on the data.

Static analysis proves an allocation *can* only happen on a guarded path. Only the recorder shows
whether it *did* stop happening. A warmup site whose count is still climbing in steady state is a
bug the checker structurally cannot find.

## How the agent is wired

The agent instruments `@AllocationsForWarmup` methods at class-load time and exposes counts over
JMX as `AllocationFlightRecorderMXBean` — total allocations, plus a per-site record with count and
first/last-seen timestamps. `LoadDriver` reads it through the platform MBean server; any JMX
console would show the same thing against a live process.

It is a **separate, shaded artifact** from the library jar. A `-javaagent` jar is appended to the
system class path with nothing beside it, so it must carry its own dependencies — with ASM
relocated, so it cannot collide with whatever version the host application already uses.

The build file here does that for you:

```kotlin
tasks.named<JavaExec>("run") {
    dependsOn(agentJar)                                    // builds :core:shadowJar
    jvmArgs("-javaagent:${agentJarFile()}")
}
```

In a real project, with the artifact published, that is just:

```
java -javaagent:core-<version>-agent.jar -jar your-app.jar
```

## Related

[`examples/steady-state-demo`](../../examples/steady-state-demo) shows the same idea with `javac`
and raw `java`, and with four threads racing through the lazy init — another thing the static
checker cannot see.
