package com.staticallocationchecker.instrument;

import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.Allocations;
import com.staticallocationchecker.TypeOracle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites every method of a class so that each allocation site reports to the
 * {@link com.staticallocationchecker.runtime.AllocationTrace} at runtime.
 *
 * <p>Every site is recorded, not only those in {@code @AllocationsForWarmup} methods. Which contract
 * a site falls under is a property of the bytecode, and the report works it out from the
 * annotations afterwards; the probe does not need to know.
 *
 * <p>At every site the shared {@link Allocations} detector recognizes, a stack-neutral
 * {@code LDC code; INVOKESTATIC recordSite(int)} pair is inserted before the allocating instruction.
 * The {@code code} is a small integer assigned by the trace, which also records the site key it
 * stands for. Because the insertion is balanced within its basic block, the existing stack-map
 * frames stay valid and only {@code maxStack} needs recomputing - with one exception, handled by
 * {@link #keepUninitializedOnTheNew}: a frame can name an object under construction by the offset of
 * its {@code NEW}, and the probe must not take that offset.
 */
public final class AllocationInstrumenter {

    private static final String RECORDER =
            "com/staticallocationchecker/runtime/AllocationTrace";

    /**
     * The same classification the static checker uses. Sharing it is the point: the two used to
     * resolve types independently, so a class could be exempt at build time and recorded at
     * runtime with nothing to catch the disagreement.
     */
    private final TypeOracle oracle;

    /** Maps a site key to the code the probe passes, normally {@code AllocationTrace::siteCode}. */
    private final ToIntFunction<String> siteCodes;

    /**
     * @param resolutionLoader classloader used to resolve the types classification depends on -
     *                         typically the loader that will define the instrumented class
     * @param siteCodes        assigns each site key its integer code
     */
    public AllocationInstrumenter(ClassLoader resolutionLoader, ToIntFunction<String> siteCodes) {
        this.oracle = TypeOracle.forClassLoader(resolutionLoader);
        this.siteCodes = siteCodes;
    }

    /** Returns instrumented bytecode, or null if the class has no allocation sites. */
    public byte[] instrument(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean changed = false;
        for (MethodNode method : classNode.methods) {
            changed |= instrumentMethod(classNode, method);
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
        boolean changed = false;
        int line = -1;
        AbstractInsnNode[] original = method.instructions.toArray();
        for (int offset = 0; offset < original.length; offset++) {
            AbstractInsnNode insn = original[offset];
            if (insn instanceof LineNumberNode) {
                line = ((LineNumberNode) insn).line;
                continue;
            }
            AllocationCategory category = oracle.categoryOf(insn);
            if (category == null) {
                continue;
            }
            // The offset is taken from the original instruction list, so it is not perturbed by the
            // probes this loop inserts and stays comparable to what the static checker would see.
            String key = Allocations.siteKey(binaryClass, method.name, line, offset, category);
            InsnList probe = new InsnList();
            probe.add(new LdcInsnNode(siteCodes.applyAsInt(key)));
            probe.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RECORDER, "recordSite", "(I)V", false));
            AbstractInsnNode probeStart = probe.getFirst();
            method.instructions.insertBefore(insn, probe);
            if (insn.getOpcode() == Opcodes.NEW) {
                keepUninitializedOnTheNew(method, probeStart, insn);
            }
            changed = true;
        }
        return changed;
    }

    /**
     * Re-points frames that name an uninitialized object at its {@code NEW}, not at the probe.
     *
     * <p>When a branch sits between {@code NEW} and the constructor call - {@code new Holder(flag ? a : b)}
     * - the stack-map frame at the branch target records the object as {@code Uninitialized(offset)},
     * where {@code offset} must be the offset of the {@code NEW}. ASM marks that offset with a label just
     * before the {@code NEW}, and the probe was inserted after the label, so the label now marks the
     * probe and the JVM rejects the class with a {@code ClassFormatError}.
     *
     * <p>The labels are left where they are, since jumps that land on them should still run the probe.
     * Instead a new label goes directly before the {@code NEW}, and only frame entries are moved to it.
     */
    private static void keepUninitializedOnTheNew(MethodNode method, AbstractInsnNode probeStart,
                                                  AbstractInsnNode newInsn) {
        Set<LabelNode> atNew = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode node = probeStart.getPrevious(); node != null && node.getOpcode() < 0;
                node = node.getPrevious()) {
            if (node instanceof LabelNode) {
                atNew.add((LabelNode) node);
            }
        }
        if (atNew.isEmpty()) {
            return;
        }
        LabelNode newLabel = new LabelNode();
        method.instructions.insertBefore(newInsn, newLabel);
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof FrameNode) {
                retarget(((FrameNode) node).local, atNew, newLabel);
                retarget(((FrameNode) node).stack, atNew, newLabel);
            }
        }
    }

    /** In a frame's types, a {@link LabelNode} means "uninitialized, created at this label". */
    private static void retarget(List<Object> types, Set<LabelNode> from, LabelNode to) {
        if (types == null) {
            return;
        }
        for (ListIterator<Object> it = types.listIterator(); it.hasNext(); ) {
            Object type = it.next();
            if (type instanceof LabelNode && from.contains(type)) {
                it.set(to);
            }
        }
    }
}
