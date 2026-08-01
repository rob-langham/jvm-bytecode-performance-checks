package com.staticallocationchecker.instrument;

import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.Allocations;
import java.util.List;
import java.util.function.Predicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites {@code @AllocationsForWarmup} methods so that each allocation site reports to the
 * {@link com.staticallocationchecker.runtime.AllocationFlightRecorder} at runtime.
 *
 * <p>At every site the shared {@link Allocations} detector recognizes, a stack-neutral
 * {@code LDC key; INVOKESTATIC recordSite(String)} pair is inserted before the allocating
 * instruction. Because the insertion is balanced within its basic block, the existing stack-map
 * frames stay valid and only {@code maxStack} needs recomputing.
 */
public final class WarmupInstrumenter {

    private static final String ALLOCATIONS_FOR_WARMUP =
            "Lcom/staticallocationchecker/annotations/AllocationsForWarmup;";
    private static final String RECORDER =
            "com/staticallocationchecker/runtime/AllocationFlightRecorder";

    private final ClassLoader resolutionLoader;

    /**
     * @param resolutionLoader classloader used to resolve allocated types for the Throwable-exemption
     *                         check (typically the loader that will define the instrumented class)
     */
    public WarmupInstrumenter(ClassLoader resolutionLoader) {
        this.resolutionLoader = resolutionLoader;
    }

    /** Returns instrumented bytecode, or null if the class has no warmup allocation sites. */
    public byte[] instrument(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean typeWarmup = hasAnnotation(classNode.visibleAnnotations);
        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            if (typeWarmup || hasAnnotation(method.visibleAnnotations)) {
                changed |= instrumentMethod(classNode, method);
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private boolean instrumentMethod(ClassNode classNode, MethodNode method) {
        String binaryClass = Type.getObjectType(classNode.name).getClassName();
        Predicate<String> isThrowable = name -> Allocations.isThrowableByReflection(name, resolutionLoader);
        boolean changed = false;
        int line = -1;
        AbstractInsnNode[] original = method.instructions.toArray();
        for (int offset = 0; offset < original.length; offset++) {
            AbstractInsnNode insn = original[offset];
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            AllocationCategory category = Allocations.categoryOf(insn, isThrowable);
            if (category == null) {
                continue;
            }
            // The offset is taken from the original instruction list, so it is not perturbed by the
            // probes this loop inserts and stays comparable to what the static checker would see.
            String key = Allocations.siteKey(binaryClass, method.name, line, offset, category);
            InsnList probe = new InsnList();
            probe.add(new LdcInsnNode(key));
            probe.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RECORDER, "recordSite", "(Ljava/lang/String;)V", false));
            method.instructions.insertBefore(insn, probe);
            changed = true;
        }
        return changed;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        return annotations != null
                && annotations.stream().anyMatch(a -> ALLOCATIONS_FOR_WARMUP.equals(a.desc));
    }
}
