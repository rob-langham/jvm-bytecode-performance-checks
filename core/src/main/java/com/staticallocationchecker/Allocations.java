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
        return categoryOf(insn, isThrowableType, call -> false);
    }

    /**
     * As {@link #categoryOf(AbstractInsnNode, Predicate)}, but able to tell a varargs call site's
     * synthesised array from an ordinary one.
     *
     * @param isVarargsCall tests whether a call site's target is declared varargs; when it cannot
     *                      say, the array is reported as the less specific {@code NEW_ARRAY}
     */
    public static AllocationCategory categoryOf(
            AbstractInsnNode insn,
            Predicate<String> isThrowableType,
            Predicate<MethodInsnNode> isVarargsCall) {
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
                return feedsAVarargsCall(insn, isVarargsCall)
                        ? AllocationCategory.VARARGS_ARRAY : AllocationCategory.NEW_ARRAY;
            case Opcodes.MULTIANEWARRAY:
                return AllocationCategory.NEW_ARRAY;
            default:
                return null;
        }
    }

    /**
     * Whether this array allocation is the array a varargs call site synthesises.
     *
     * <p>Bytecode alone cannot answer this: {@code f(1, 2, 3)} and {@code f(new int[] {1, 2, 3})}
     * compile identically. The only real signal is {@code ACC_VARARGS} on the callee, so this walks
     * forward over the array-filling sequence to the call being fed and asks about its target.
     */
    private static boolean feedsAVarargsCall(
            AbstractInsnNode arrayAllocation, Predicate<MethodInsnNode> isVarargsCall) {
        for (AbstractInsnNode next = arrayAllocation.getNext(); next != null; next = next.getNext()) {
            if (next instanceof MethodInsnNode call) {
                Type[] parameters = Type.getArgumentTypes(call.desc);
                boolean lastParameterIsArray = parameters.length > 0
                        && parameters[parameters.length - 1].getSort() == Type.ARRAY;
                return lastParameterIsArray && isVarargsCall.test(call);
            }
            if (!isArrayFillingInstruction(next)) {
                return false;
            }
        }
        return false;
    }

    /** The instructions javac emits between allocating a varargs array and passing it. */
    private static boolean isArrayFillingInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode < 0) {
            return true; // labels, line numbers and frames carry no semantics
        }
        return opcode == Opcodes.DUP
                || (opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.LDC)
                || (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
                || (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE);
    }

    /** Whether a call site's target is a varargs method, resolved through {@code loader}. */
    public static boolean isVarargsByReflection(MethodInsnNode call, ClassLoader loader) {
        try {
            Class<?> owner = Class.forName(Type.getObjectType(call.owner).getClassName(), false, loader);
            Type[] parameters = Type.getArgumentTypes(call.desc);
            for (java.lang.reflect.Executable candidate : candidates(owner, call.name)) {
                if (candidate.isVarArgs() && candidate.getParameterCount() == parameters.length) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.lang.reflect.Executable[] candidates(Class<?> owner, String methodName) {
        if ("<init>".equals(methodName)) {
            return owner.getDeclaredConstructors();
        }
        return java.util.Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toArray(java.lang.reflect.Executable[]::new);
    }

    /**
     * The stable, human-readable key identifying an allocation site.
     *
     * <p>Class, method, line and category alone do not identify a site: two allocations of the same
     * category can share a source line, and with no debug information every site in a method shares
     * a line of -1. Collapsing those together would make "one site fired twice" and "two sites fired
     * once each" indistinguishable, which is the distinction the recorder exists to draw. The
     * bytecode offset disambiguates them and is stable for a given compiled class.
     *
     * @param bytecodeOffset instruction index of the allocation within its method
     */
    public static String siteKey(
            String binaryClassName,
            String methodName,
            int line,
            int bytecodeOffset,
            AllocationCategory category) {
        return binaryClassName + "#" + methodName + ":" + line + "@" + bytecodeOffset + ":" + category;
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
