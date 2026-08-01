package com.staticallocationchecker;

import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Shared classification of allocation-producing bytecode instructions, used by both the static
 * {@link AllocationChecker} and the runtime instrumenter so that "what counts as an allocation"
 * is defined in exactly one place.
 */
public final class Allocations {

    private static final Set<String> WRAPPER_TYPES = Set.of(
            "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
            "java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double");

    private static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private Allocations() {
    }

    /**
     * The allocation category of an instruction, or null if it does not allocate (or is exempt).
     *
     * @param isThrowableType tests whether an internal class name is a {@link Throwable} subtype;
     *                        {@code new} of such a type is exempt (exceptional paths are not hot)
     */
    public static AllocationCategory categoryOf(AbstractInsnNode insn, Predicate<String> isThrowableType) {
        if (insn instanceof InvokeDynamicInsnNode indy) {
            return invokeDynamicCategory(indy);
        }
        if (insn instanceof MethodInsnNode call && isBoxing(call)) {
            return AllocationCategory.BOXING;
        }
        switch (insn.getOpcode()) {
            case Opcodes.NEW:
                return isThrowableType.test(((TypeInsnNode) insn).desc) ? null : AllocationCategory.NEW;
            case Opcodes.NEWARRAY:
            case Opcodes.ANEWARRAY:
            case Opcodes.MULTIANEWARRAY:
                return AllocationCategory.NEW_ARRAY;
            default:
                return null;
        }
    }

    /** The stable, human-readable key identifying an allocation site. */
    public static String siteKey(String binaryClassName, String methodName, int line, AllocationCategory category) {
        return binaryClassName + "#" + methodName + ":" + line + ":" + category;
    }

    /** Whether {@code internalName} resolves, via {@code loader}, to a subtype of {@link Throwable}. */
    public static boolean isThrowableByReflection(String internalName, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, loader);
            return Throwable.class.isAssignableFrom(type);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The allocation category of an invokedynamic, or null if it does not allocate. */
    private static AllocationCategory invokeDynamicCategory(InvokeDynamicInsnNode indy) {
        String bootstrapOwner = indy.bsm.getOwner();
        if (STRING_CONCAT_FACTORY.equals(bootstrapOwner)) {
            return AllocationCategory.STRING_CONCAT;
        }
        if (LAMBDA_METAFACTORY.equals(bootstrapOwner)) {
            // The indy descriptor's argument types are the captured values. A non-capturing
            // lambda (no arguments) links to a cached singleton and does not allocate.
            boolean capturing = Type.getArgumentTypes(indy.desc).length > 0;
            return capturing ? AllocationCategory.LAMBDA : null;
        }
        return null;
    }

    /** Whether a call is an autoboxing conversion (Wrapper.valueOf(primitive)). */
    private static boolean isBoxing(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && WRAPPER_TYPES.contains(call.owner)
                && call.name.equals("valueOf")
                && Type.getArgumentTypes(call.desc).length == 1
                && Type.getArgumentTypes(call.desc)[0].getSort() <= Type.DOUBLE;
    }
}
