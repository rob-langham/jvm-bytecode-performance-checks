package com.staticallocationchecker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.objectweb.asm.Opcodes;

/**
 * What class-file versions this build of the checker can actually read.
 *
 * <p>The ceiling is ASM's, not ours: {@code ClassReader} rejects anything newer than the highest
 * {@code Opcodes.V*} constant it ships with. So the ceiling is read back off ASM by reflection
 * rather than written down here as a second number, which would silently rot the moment the ASM
 * pin in {@code core/build.gradle.kts} moves. One source of truth, and it is the one that decides.
 */
final class BytecodeSupport {

    /**
     * The highest class-file major version ASM will parse. Derived from the {@code V1_1}/{@code V9}
     * -style constants; the low 16 bits carry the major, the high bits a minor ASM uses for the
     * ancient {@code 1.x} names and for preview flags.
     */
    static final int MAX_SUPPORTED_MAJOR = highestMajorKnownToAsm();

    private BytecodeSupport() {
    }

    /** The Java release a class-file major version corresponds to: 52 is Java 8, 69 is Java 25. */
    static int javaRelease(int classFileMajor) {
        return classFileMajor - 44;
    }

    /**
     * The message shown when a class file is newer than ASM understands. It has to say plainly that
     * the user did nothing wrong - the only fix is a checker built against a newer ASM - because the
     * default reading of "failed to parse" is "my class file is broken", and it is not.
     */
    static String tooNewMessage(String description, int foundMajor) {
        return "Cannot analyse " + description + ": it is class-file major version " + foundMajor
                + " (Java " + javaRelease(foundMajor) + "), but this build of"
                + " static-allocation-checker only understands up to major "
                + MAX_SUPPORTED_MAJOR + " (Java " + javaRelease(MAX_SUPPORTED_MAJOR) + ")."
                + " This is not a problem with your code or your build: support for newer bytecode"
                + " only arrives with a newer ASM, so upgrade static-allocation-checker to a release"
                + " built against an ASM that knows Java " + javaRelease(foundMajor)
                + " (or compile with --release " + javaRelease(MAX_SUPPORTED_MAJOR) + " or lower).";
    }

    /**
     * Recognises ASM's own rejection. ASM signals it as a plain {@link IllegalArgumentException}
     * with this exact wording and no dedicated type, so matching the message is the only hook there
     * is; if a future ASM rewords it we fall back to the generic parse failure rather than lying.
     */
    static boolean isUnsupportedVersion(Throwable e) {
        return e instanceof IllegalArgumentException
                && e.getMessage() != null
                && e.getMessage().startsWith("Unsupported class file major version");
    }

    /** The major version stamped in bytes 6-7 of a class file, or -1 if it is too short to have one. */
    static int majorVersionOf(byte[] classBytes) {
        if (classBytes.length < 8) {
            return -1;
        }
        return ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
    }

    private static int highestMajorKnownToAsm() {
        int highest = 0;
        for (Field field : Opcodes.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            if (!field.getName().matches("V\\d+(_\\d+)?")) {
                continue;
            }
            try {
                highest = Math.max(highest, field.getInt(null) & 0xFFFF);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + field.getName() + " from ASM", e);
            }
        }
        if (highest == 0) {
            throw new IllegalStateException(
                    "Found no V* class-file version constants on ASM's Opcodes; the checker cannot"
                            + " tell what bytecode it supports.");
        }
        return highest;
    }
}
