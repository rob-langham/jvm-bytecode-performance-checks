package com.staticallocationchecker.instrument;

import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.Allocations;
import com.staticallocationchecker.TypeOracle;
import java.util.function.ToIntFunction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
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
 * frames stay valid and only {@code maxStack} needs recomputing.
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
            method.instructions.insertBefore(insn, probe);
            changed = true;
        }
        return changed;
    }
}
