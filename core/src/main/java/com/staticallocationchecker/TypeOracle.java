package com.staticallocationchecker;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Answers the two questions classification cannot decide from an instruction alone: whether an
 * allocated type is a {@link Throwable}, and whether a call site's target is varargs.
 *
 * <p>Both answers depend on <em>other</em> classes, and the static checker and the runtime agent
 * have different amounts of information available - the checker has an index of everything it was
 * pointed at, the agent has one class at a time. They previously each implemented their own
 * resolution, which meant the same code could be classified one way at build time and another at
 * runtime, with nothing to catch it.
 *
 * <p>So the algorithm lives here, once, and the difference is confined to a {@link ClassSource}.
 * Both callers walk supertypes the same way and fall back the same way; only the reachable data
 * differs, which is a real difference in what is knowable rather than a difference in policy.
 */
public final class TypeOracle {

    private static final String THROWABLE = "java/lang/Throwable";
    private static final String OBJECT = "java/lang/Object";

    /** Supplies a class's bytecode by internal name, or null when it cannot be found. */
    @FunctionalInterface
    public interface ClassSource {
        ClassNode find(String internalName);
    }

    private final ClassSource source;
    private final ClassLoader fallbackLoader;
    private final Map<String, Boolean> throwableCache = new HashMap<>();

    private TypeOracle(ClassSource source, ClassLoader fallbackLoader) {
        this.source = source;
        this.fallbackLoader = fallbackLoader;
    }

    /**
     * For the static checker: consult the index first, then the loader's resources, then
     * reflection. The index is authoritative for code under analysis, which may not be loadable
     * by this JVM at all - the usual case when a build plugin analyses somebody else's project.
     */
    public static TypeOracle forIndex(Map<String, ClassNode> index, ClassLoader loader) {
        ClassSource indexed = index::get;
        ClassSource resources = resourceSource(loader);
        return new TypeOracle(
            name -> {
                ClassNode found = indexed.find(name);
                return found != null ? found : resources.find(name);
            },
            loader);
    }

    /**
     * For the runtime agent, which has no index: read the class bytes as resources from the loader
     * that is defining the class being instrumented.
     */
    public static TypeOracle forClassLoader(ClassLoader loader) {
        return new TypeOracle(resourceSource(loader), loader);
    }

    /**
     * Reads a class as a resource rather than loading it. Deliberately not {@code Class.forName}:
     * this runs inside a class-file transformer, where triggering further class loading is a way
     * to deadlock. Returns null for classes that are not resources - notably the JDK's own, which
     * are not readable this way under the module system, and which the reflective fallback covers.
     */
    private static ClassSource resourceSource(ClassLoader loader) {
        return internalName -> {
            ClassLoader effective = loader != null ? loader : ClassLoader.getSystemClassLoader();
            try (InputStream in = effective.getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    return null;
                }
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes())
                    .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        };
    }

    /** Whether {@code internalName} is a subtype of {@link Throwable}, so its allocation is exempt. */
    public boolean isThrowable(String internalName) {
        Boolean cached = throwableCache.get(internalName);
        if (cached != null) {
            return cached;
        }
        boolean result = computeIsThrowable(internalName);
        throwableCache.put(internalName, result);
        return result;
    }

    private boolean computeIsThrowable(String internalName) {
        String current = internalName;
        while (current != null) {
            if (THROWABLE.equals(current)) {
                return true;
            }
            if (OBJECT.equals(current)) {
                return false;
            }
            ClassNode node = source.find(current);
            if (node == null) {
                // The chain ran out of readable classes; ask the runtime about this link only.
                return Allocations.isThrowableByReflection(current, fallbackLoader);
            }
            current = node.superName;
        }
        return false;
    }

    /** Whether a call site's target is declared varargs, so its trailing array is synthesised. */
    public boolean isVarargs(MethodInsnNode call) {
        String current = call.owner;
        while (current != null) {
            ClassNode node = source.find(current);
            if (node == null) {
                return Allocations.isVarargsByReflection(call, fallbackLoader);
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals(call.name) && method.desc.equals(call.desc)) {
                    return (method.access & org.objectweb.asm.Opcodes.ACC_VARARGS) != 0;
                }
            }
            current = node.superName;
        }
        return false;
    }

    /** The category of an allocation instruction, resolved through this oracle. */
    public AllocationCategory categoryOf(org.objectweb.asm.tree.AbstractInsnNode insn) {
        return Allocations.categoryOf(insn, this::isThrowable, this::isVarargs);
    }

    @Override
    public String toString() {
        return "TypeOracle[" + Type.getObjectType(OBJECT).getClassName() + " rooted]";
    }
}
