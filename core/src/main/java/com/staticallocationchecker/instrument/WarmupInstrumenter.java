package com.staticallocationchecker.instrument;

import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.Allocations;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
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
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

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

        boolean changed = false;
        for (MethodNode method : warmupMethods(classNode)) {
            changed |= instrumentMethod(classNode, method);
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The methods whose allocations count as warmup: those annotated directly (or via the type),
     * plus the synthetic bodies of any lambdas they create.
     *
     * <p>A lambda body compiles to a separate synthetic method that carries no annotation of its
     * own, so without this a warmup method that does its work inside a lambda would record nothing.
     * The search is transitive, because a lambda body can create further lambdas.
     *
     * <p>Only <em>synthetic</em> implementations are swept in. A method reference such as
     * {@code this::existing} also links through {@code LambdaMetafactory}, but its target is an
     * ordinary method with its own identity and its own contract - it is not warmup code merely
     * because a warmup method happened to reference it.
     */
    private static Set<MethodNode> warmupMethods(ClassNode classNode) {
        boolean typeWarmup = hasAnnotation(classNode.visibleAnnotations);
        Set<MethodNode> selected = new LinkedHashSet<>();
        Deque<MethodNode> pending = new ArrayDeque<>();
        for (MethodNode method : classNode.methods) {
            if (typeWarmup || hasAnnotation(method.visibleAnnotations)) {
                selected.add(method);
                pending.add(method);
            }
        }
        while (!pending.isEmpty()) {
            for (AbstractInsnNode insn : pending.poll().instructions) {
                MethodNode body = lambdaBody(classNode, insn);
                if (body != null && selected.add(body)) {
                    pending.add(body);
                }
            }
        }
        return selected;
    }

    /** The synthetic method implementing the lambda an instruction creates, if it is in this class. */
    private static MethodNode lambdaBody(ClassNode classNode, AbstractInsnNode insn) {
        if (!(insn instanceof InvokeDynamicInsnNode indy)
                || !LAMBDA_METAFACTORY.equals(indy.bsm.getOwner())
                || indy.bsmArgs.length < 2
                || !(indy.bsmArgs[1] instanceof Handle implementation)
                || !classNode.name.equals(implementation.getOwner())) {
            return null;
        }
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(implementation.getName())
                    && method.desc.equals(implementation.getDesc())
                    && (method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                return method;
            }
        }
        return null;
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
