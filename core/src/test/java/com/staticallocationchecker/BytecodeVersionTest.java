package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.fixtures.DirectNew;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

/**
 * How far into the future the checker can read, and what it says when it runs out of road.
 *
 * <p>A class file compiled by a javac newer than the bundled ASM is the one failure a user cannot
 * fix in their own code, so the message has to say so out loud. These tests forge the version
 * stamp - bytes 6-7 of the class file - rather than needing a JDK of each vintage installed.
 */
class BytecodeVersionTest {

    private static final int JAVA_25_MAJOR = 69;

    private static final String DIRECT_NEW_RESOURCE =
            DirectNew.class.getName().replace('.', '/') + ".class";

    @Test
    void supportsAtLeastJava25Bytecode() {
        assertTrue(BytecodeSupport.MAX_SUPPORTED_MAJOR >= JAVA_25_MAJOR,
                () -> "the pinned ASM tops out at class-file major "
                        + BytecodeSupport.MAX_SUPPORTED_MAJOR + ", below Java 25's " + JAVA_25_MAJOR
                        + "; bump asmVersion in core/build.gradle.kts");
    }

    @Test
    void reportsTheCeilingAsmItselfAdvertises() throws Exception {
        // Tied to ASM, not to a literal: an ASM bump moves both sides together, and this test is
        // the place where that movement has to be noticed rather than assumed.
        assertEquals(highestVersionConstantOnOpcodes(), BytecodeSupport.MAX_SUPPORTED_MAJOR,
                "the advertised ceiling must be the highest version ASM knows about");
    }

    @Test
    void analysesAClassStampedAtTheSupportedCeiling(@TempDir Path dir) throws IOException {
        writeFixtureStampedAs(dir.resolve("AtCeiling.class"), BytecodeSupport.MAX_SUPPORTED_MAJOR);

        Report report = analyze(dir);

        assertFalse(report.isClean(),
                "a class at the supported ceiling must be read, not skipped or rejected");
        assertEquals(1, report.findings().size(), () -> "got " + report.findings());
    }

    @Test
    void explainsWhoNeedsUpgradingWhenTheClassIsTooNew(@TempDir Path dir) throws IOException {
        int tooNew = BytecodeSupport.MAX_SUPPORTED_MAJOR + 1;
        writeFixtureStampedAs(dir.resolve("FromTheFuture.class"), tooNew);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> analyze(dir));
        String message = thrown.getMessage();

        assertTrue(message.contains("FromTheFuture.class"), message);
        assertTrue(message.contains("major version " + tooNew), message);
        assertTrue(message.contains("Java " + BytecodeSupport.javaRelease(tooNew)), message);
        assertTrue(message.contains("up to major " + BytecodeSupport.MAX_SUPPORTED_MAJOR), message);
        assertTrue(message.contains("not a problem with your code"),
                "the user must not be sent hunting a bug in their own build: " + message);
        assertTrue(message.contains("upgrade static-allocation-checker"),
                "the message has to name the fix, not just the symptom: " + message);
    }

    @Test
    void stillReportsAPlainParseFailureAsSuch(@TempDir Path dir) throws IOException {
        // A plausible header - magic, and a version well inside the supported range - followed by
        // nonsense, so the failure can only be corruption and not the class being from the future.
        Files.write(dir.resolve("Corrupt.class"), new byte[] {
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 9, 9, 9, 9});

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> analyze(dir));

        assertFalse(String.valueOf(thrown.getMessage()).contains("upgrade static-allocation-checker"),
                "genuine corruption must not be blamed on the checker's age: " + thrown.getMessage());
    }

    private static Report analyze(Path root) {
        return new AllocationChecker().analyze(List.of(root), List.of());
    }

    /** Rewrites the class-file version stamp in place, the way a newer javac would have written it. */
    private static void writeFixtureStampedAs(Path target, int major) throws IOException {
        byte[] bytes = Files.readAllBytes(testClassesRoot().resolve(DIRECT_NEW_RESOURCE));
        bytes[6] = (byte) (major >>> 8);
        bytes[7] = (byte) major;
        Files.write(target, bytes);
    }

    private static int highestVersionConstantOnOpcodes() throws IllegalAccessException {
        int highest = 0;
        for (Field field : Opcodes.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == int.class
                    && field.getName().matches("V\\d+(_\\d+)?")) {
                highest = Math.max(highest, field.getInt(null) & 0xFFFF);
            }
        }
        return highest;
    }
}
