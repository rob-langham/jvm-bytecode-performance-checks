package com.staticallocationchecker.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Attaches the agent to a JVM that is already running, and checks that a class loaded before the
 * attach is instrumented afterwards.
 *
 * <p>The fake-{@code Instrumentation} unit test proves {@code agentmain} asks for retransformation.
 * Only this proves the JVM honours it: that the manifest permits retransformation, that the
 * transformer produces bytecode the verifier accepts on a <em>redefinition</em> rather than a fresh
 * load, and that the probes land in a class whose original definition had none.
 */
class DynamicAttachTest {

    private static Path agentJar;
    private static Path harnessClasses;

    @BeforeAll
    static void locateArtefacts() {
        agentJar = Path.of(System.getProperty("agentJar"));
        harnessClasses = Path.of(System.getProperty("harnessClasses"));
        assertTrue(Files.isRegularFile(agentJar), () -> "agent jar not built: " + agentJar);
    }

    @Test
    @Timeout(180)
    void attachingInstrumentsClassesLoadedBeforeTheAgentArrived(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("go");
        Process process = startHarnessWithoutAgent(marker);
        List<String> output = new ArrayList<>();

        try (BufferedReader reader = process.inputReader()) {
            assertEquals("LOADED", awaitLine(reader, output),
                    () -> "harness did not reach its pre-attach state: " + output);

            VirtualMachine vm = VirtualMachine.attach(String.valueOf(process.pid()));
            try {
                vm.loadAgent(agentJar.toString());
            } finally {
                vm.detach();
            }

            Files.createFile(marker);

            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }

        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the harness JVM did not exit");
        assertEquals(0, process.exitValue(), () -> "harness failed:\n" + String.join("\n", output));
        assertTrue(output.contains("REGISTERED=true"),
                () -> "attaching should register the MXBean: " + output);
        assertTrue(output.contains("TOTAL=1"),
                () -> "the warmup allocation after attach should be recorded, which requires the "
                        + "already-loaded class to have been retransformed: " + output);
    }

    private Process startHarnessWithoutAgent(Path marker) throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        // No -javaagent. The agent jar is on the class path only so the harness can compile
        // against the annotations, exactly as a real application would depend on them.
        ProcessBuilder builder = new ProcessBuilder(
                javaBinary.toString(),
                "-cp", agentJar + File.pathSeparator + harnessClasses,
                AttachHarness.class.getName(),
                marker.toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static String awaitLine(BufferedReader reader, List<String> output) throws Exception {
        String line = reader.readLine();
        assertNotNull(line, () -> "harness produced no output: " + output);
        output.add(line);
        return line;
    }
}
