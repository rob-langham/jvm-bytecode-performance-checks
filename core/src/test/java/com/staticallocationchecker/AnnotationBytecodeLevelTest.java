package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import com.staticallocationchecker.annotations.ZeroAllocations;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two annotations must be class-file major 52 (Java 8).
 *
 * <p>This is not a style preference. A project building with {@code --release 8} cannot have a
 * major-61 class file anywhere on its compile classpath: javac rejects it outright, so the whole
 * jar becomes unusable and the annotations - the part of this library such a project compiles
 * against - cannot be applied at all. That would leave the checker unable to serve exactly the
 * oldest bytecode it claims to support. The whole of {@code main} is now compiled at release 8,
 * configured in {@code core/build.gradle.kts}; this test is what stops it being quietly undone.
 */
class AnnotationBytecodeLevelTest {

    /** Java 8. The annotations are bare @interface declarations, so nothing newer is needed. */
    private static final int JAVA_8_MAJOR = 52;

    @Test
    void annotationsAreCompiledForJava8() throws IOException {
        assertEquals(JAVA_8_MAJOR, majorVersionOf(ZeroAllocations.class), why("ZeroAllocations"));
        assertEquals(
                JAVA_8_MAJOR, majorVersionOf(AllocationsForWarmup.class), why("AllocationsForWarmup"));
    }

    /**
     * The proof rather than the proxy: a source file using {@code @ZeroAllocations} is compiled with
     * {@code --release 8} against the built annotation classes. If they ever regress to major 61
     * this fails with javac's own "bad class file" complaint, which is what a release-8 consumer
     * would see.
     */
    @Test
    void aRelease8ProjectCanCompileAgainstTheAnnotations(@TempDir Path outputDir)
            throws IOException, URISyntaxException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "No java compiler on this JVM - the test needs a JDK, not a JRE.");

        JavaFileObject source =
                new InMemorySource(
                        "HotPath",
                        "import com.staticallocationchecker.annotations.ZeroAllocations;\n"
                                + "public class HotPath {\n"
                                + "    @ZeroAllocations\n"
                                + "    public long tick(long id) { return id + 1; }\n"
                                + "}\n");

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        boolean compiled;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            compiled =
                    compiler.getTask(
                                    new java.io.OutputStreamWriter(diagnostics, StandardCharsets.UTF_8),
                                    files,
                                    null,
                                    List.of(
                                            "--release",
                                            "8",
                                            "-classpath",
                                            annotationClasspath(),
                                            "-d",
                                            outputDir.toString()),
                                    null,
                                    List.of(source))
                            .call();
        }

        assertTrue(
                compiled,
                "A project compiling with --release 8 could not compile against the annotations, so"
                        + " the checker is unusable on the oldest bytecode it claims to support."
                        + " javac said:\n"
                        + diagnostics.toString(StandardCharsets.UTF_8));
    }

    private static String why(String annotation) {
        return "com.staticallocationchecker.annotations."
                + annotation
                + " is not class-file major "
                + JAVA_8_MAJOR
                + " (Java 8). Projects compiling with --release 8 cannot put a newer class file on"
                + " their compile classpath, so this makes the annotation - and therefore the whole"
                + " checker - unusable for the oldest target the tool supports. See the release-8"
                + " main compilation in core/build.gradle.kts.";
    }

    private static int majorVersionOf(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Cannot find the compiled class file for " + type.getName());
            byte[] header = in.readNBytes(8);
            return BytecodeSupport.majorVersionOf(header);
        }
    }

    /** Wherever the annotation classes were loaded from - a directory in the build, or a jar. */
    private static String annotationClasspath() throws URISyntaxException {
        File location =
                new File(ZeroAllocations.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return location.getAbsolutePath();
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String name, String code) {
            super(URI.create("string:///" + name + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
