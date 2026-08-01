package com.staticallocationchecker;

import static com.staticallocationchecker.Fixtures.testClassesRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * The seam between the static checker and the runtime instrumenter.
 *
 * <p>They resolve types from different places - an index versus a classloader - and nothing used to
 * check that they reached the same conclusions. A class exempt at build time and recorded at
 * runtime is a contradiction the user would have to debug from the outside.
 */
class TypeOracleTest {

    private static final ClassLoader LOADER = TypeOracleTest.class.getClassLoader();

    private static Map<String, ClassNode> fixtureIndex() {
        Map<String, ClassNode> index = new HashMap<>();
        try (Stream<Path> paths = Files.walk(testClassesRoot())) {
            paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    ClassNode node = new ClassNode();
                    new ClassReader(Files.readAllBytes(p)).accept(node, ClassReader.SKIP_CODE);
                    index.putIfAbsent(node.name, node);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    private TypeOracle checkerOracle() {
        return TypeOracle.forIndex(fixtureIndex(), LOADER);
    }

    private TypeOracle agentOracle() {
        return TypeOracle.forClassLoader(LOADER);
    }

    /** Types the two must agree on, spanning JDK, user-defined, and non-Throwable. */
    private static List<String> typesUnderTest() {
        return List.of(
            "java/lang/Throwable",
            "java/lang/IllegalStateException",
            "java/lang/OutOfMemoryError",
            "java/io/IOException",
            "java/lang/Object",
            "java/util/ArrayList",
            "java/lang/String",
            "com/staticallocationchecker/fixtures/ExceptionAllocation$CustomException",
            "com/staticallocationchecker/fixtures/DirectNew");
    }

    @Test
    void theCheckerAndTheAgentAgreeOnEveryThrowableQuestion() {
        TypeOracle checker = checkerOracle();
        TypeOracle agent = agentOracle();

        List<String> disagreements = new ArrayList<>();
        for (String type : typesUnderTest()) {
            boolean fromChecker = checker.isThrowable(type);
            boolean fromAgent = agent.isThrowable(type);
            if (fromChecker != fromAgent) {
                disagreements.add(type + ": checker=" + fromChecker + " agent=" + fromAgent);
            }
        }

        assertEquals(List.of(), disagreements,
                "a type exempt at build time and recorded at runtime is a contradiction the user "
                        + "has to debug from the outside");
    }

    @Test
    void resolvesUserDefinedExceptionsThroughTheirOwnHierarchy() {
        String custom = "com/staticallocationchecker/fixtures/ExceptionAllocation$CustomException";

        assertTrue(checkerOracle().isThrowable(custom),
                "extends RuntimeException, several links up from Throwable");
        assertTrue(agentOracle().isThrowable(custom));
    }

    @Test
    void doesNotTreatOrdinaryTypesAsThrowable() {
        assertFalse(checkerOracle().isThrowable("com/staticallocationchecker/fixtures/DirectNew"));
        assertFalse(agentOracle().isThrowable("com/staticallocationchecker/fixtures/DirectNew"));
    }

    @Test
    void anUnresolvableTypeIsNotExempt() {
        assertFalse(checkerOracle().isThrowable("com/example/NeverExisted"),
                "unknown must not mean exempt - that would silence a real allocation");
        assertFalse(agentOracle().isThrowable("com/example/NeverExisted"));
    }

    @Test
    void theCheckerAndTheAgentAgreeOnVarargs() {
        MethodInsnNode varargsCall = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "com/staticallocationchecker/fixtures/Varargs",
                "count", "([I)I", false);
        MethodInsnNode ordinaryCall = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "com/staticallocationchecker/fixtures/Varargs",
                "total", "([I)I", false);

        assertTrue(checkerOracle().isVarargs(varargsCall));
        assertTrue(agentOracle().isVarargs(varargsCall),
                "the agent reads the class as a resource; it must reach the same flag");
        assertFalse(checkerOracle().isVarargs(ordinaryCall));
        assertFalse(agentOracle().isVarargs(ordinaryCall));
    }

    @Test
    void agreesOnAJdkVarargsMethodNeitherCanReadAsAResource() {
        MethodInsnNode jdkVarargs = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/String", "format",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);

        assertEquals(checkerOracle().isVarargs(jdkVarargs), agentOracle().isVarargs(jdkVarargs),
                "JDK classes are not readable as resources under the module system, so both fall "
                        + "back the same way - which is the point of sharing the fallback");
    }

    @Test
    void theOracleDoesNotInitialiseTheClassesItInspects() {
        // ExplodesOnInitialisation throws from its static initialiser. Resolving it at all proves
        // the walk reads bytecode rather than loading and initialising.
        String name = AllocationsTest.ExplodesOnInitialisation.class.getName().replace('.', '/');

        assertTrue(agentOracle().isThrowable(name));
        assertTrue(checkerOracle().isThrowable(name));
    }
}
