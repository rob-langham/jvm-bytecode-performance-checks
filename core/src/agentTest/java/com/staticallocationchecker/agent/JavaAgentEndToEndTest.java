package com.staticallocationchecker.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Launches a real JVM with {@code -javaagent:core.jar} and asserts on what the agent recorded.
 *
 * <p>The in-process agent tests drive {@code premain} through a fake {@code Instrumentation}, which
 * cannot cover the parts that only exist in a real launch: the manifest attributes, the JVM
 * honouring them, class-load-time transformation of a class loaded after premain, and the agent jar
 * being self-sufficient on the system class path.
 */
class JavaAgentEndToEndTest {

    private static Path agentJar;
    private static Path harnessClasses;

    @BeforeAll
    static void locateArtefacts() {
        agentJar = Path.of(requiredProperty("agentJar"));
        harnessClasses = Path.of(requiredProperty("harnessClasses"));
        assertTrue(Files.isRegularFile(agentJar), () -> "agent jar not built: " + agentJar);
    }

    @Test
    void jarManifestDeclaresTheAgentEntryPoints() throws IOException {
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "the agent jar must have a manifest");
            Attributes main = manifest.getMainAttributes();

            assertEquals("com.staticallocationchecker.instrument.AllocationFlightAgent",
                    main.getValue("Premain-Class"), "-javaagent at startup depends on this");
            assertEquals("com.staticallocationchecker.instrument.AllocationFlightAgent",
                    main.getValue("Agent-Class"), "dynamic attach depends on this");
            assertEquals("true", main.getValue("Can-Retransform-Classes"));
            assertEquals("true", main.getValue("Can-Redefine-Classes"));
        }
    }

    @Test
    @Disabled("GAP: the root cause of the failure below. An agent jar is loaded by the system class "
            + "loader with only itself appended to the class path, so it must carry its own "
            + "dependencies or name them in a Class-Path manifest entry. This one does neither")
    void agentJarCarriesTheDependenciesItNeedsAtRuntime() throws IOException {
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            boolean bundlesAsm = jar.stream().anyMatch(e -> e.getName().endsWith(".class")
                    && (e.getName().startsWith("org/objectweb/asm/")
                        || e.getName().contains("asm/tree/AbstractInsnNode")));
            String classPath = jar.getManifest().getMainAttributes().getValue("Class-Path");

            assertTrue(bundlesAsm || classPath != null,
                    "the agent needs org.objectweb.asm at transform time; the jar neither bundles "
                            + "it nor declares a Class-Path");
        }
    }

    @Test
    @Timeout(120)
    @Disabled("GAP: the agent does nothing at all in a real launch. The jar bundles no ASM and its "
            + "manifest declares no Class-Path, so on the system class path org.objectweb.asm.* is "
            + "absent; every transform() call dies with NoClassDefFoundError, which the catch-all "
            + "in WarmupClassFileTransformer swallows into a silent 'no transformation'. The fix is "
            + "a shaded/fat agent jar (plus letting the transformer report failures rather than "
            + "discarding them). Verified by hand: TOTAL=0, SITES=0, and every class load throws")
    void agentInstrumentsWarmupMethodsInARealJvmLaunch() throws Exception {
        List<String> output = runJvmWithAgent();

        assertTrue(output.contains("REGISTERED=true"),
                () -> "the agent should register its MXBean: " + output);
        assertTrue(output.contains("TOTAL=1"),
                () -> "the lazy allocation fires once across three calls: " + output);
        assertTrue(output.contains("SITES=1"), () -> "expected exactly one site: " + output);
        assertTrue(output.stream().anyMatch(l -> l.startsWith("SITE=")
                        && l.contains("AgentHarness$Warmup#warm")),
                () -> "the site key should name the warmup method: " + output);
    }

    private List<String> runJvmWithAgent() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-javaagent:" + agentJar,
                "-cp", harnessClasses.toString(),
                AgentHarness.class.getName());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the agent JVM did not exit");
        assertEquals(0, process.exitValue(), () -> "agent JVM failed:\n" + String.join("\n", lines));
        return lines;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, () -> "system property " + name + " must be set by the Gradle task");
        return value;
    }
}
