package com.staticallocationchecker;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Shared classification of allocation-producing bytecode instructions, used by both the static
 * {@link AllocationChecker} and the runtime instrumenter so that "what counts as an allocation"
 * is defined in exactly one place.
 */
public final class Allocations {

    private static final Set<String> WRAPPER_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
                    "java/lang/Character", "java/lang/Boolean", "java/lang/Float",
                    "java/lang/Double")));

    private static final String STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String OBJECT_METHODS = "java/lang/runtime/ObjectMethods";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";

    /** Marks a stack effect this classifier does not model, so the walk must give up. */
    private static final int UNMODELLED = Integer.MIN_VALUE;

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
        if (insn instanceof InvokeDynamicInsnNode) {
            return invokeDynamicCategory((InvokeDynamicInsnNode) insn);
        }
        if (insn instanceof MethodInsnNode && isBoxing((MethodInsnNode) insn)) {
            return AllocationCategory.BOXING;
        }
        switch (insn.getOpcode()) {
            case Opcodes.NEW:
                String allocatedType = ((TypeInsnNode) insn).desc;
                if (STRING_BUILDER.equals(allocatedType) && stringConcatChain(insn) != null) {
                    return AllocationCategory.STRING_CONCAT;
                }
                return isThrowableType.test(allocatedType) ? null : AllocationCategory.NEW;
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
     * forward to the call that actually <em>consumes</em> the array and asks about its target.
     *
     * <p>Finding that call means tracking the stack, not stopping at the next {@code invoke}: the
     * element expressions between the allocation and the call may themselves call things -
     * {@code Integer.valueOf}, a constructor, even another varargs call. Those calls push their
     * results back for an {@code AASTORE}, so the array is still on the stack underneath them.
     *
     * <p>The array is the trailing varargs argument exactly when nothing else is stacked above it
     * at the call. Any shape this cannot model - a branch inside an element expression, the array
     * being stored to a local - gives up and reports the less specific {@code NEW_ARRAY}, which is
     * a weaker finding but never a missing one.
     */
    private static boolean feedsAVarargsCall(
            AbstractInsnNode arrayAllocation, Predicate<MethodInsnNode> isVarargsCall) {
        int depth = 1; // stack slots above the array, counting the array itself
        for (AbstractInsnNode insn = arrayAllocation.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                int popped = callPops(call);
                if (depth - popped < 1) {
                    return depth == 1 && lastParameterIsArray(call.desc) && isVarargsCall.test(call);
                }
                depth += Type.getReturnType(call.desc).getSize() - popped;
                continue;
            }
            if (insn instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                int popped = argumentSlots(indy.desc);
                if (depth - popped < 1) {
                    return false; // an indy call site is never a varargs call site
                }
                depth += Type.getReturnType(indy.desc).getSize() - popped;
                continue;
            }
            int delta = stackDelta(insn);
            if (delta == UNMODELLED || depth + delta < 1) {
                return false;
            }
            depth += delta;
        }
        return false;
    }

    private static boolean lastParameterIsArray(String descriptor) {
        Type[] parameters = Type.getArgumentTypes(descriptor);
        return parameters.length > 0 && parameters[parameters.length - 1].getSort() == Type.ARRAY;
    }

    /**
     * Whether this call is part of a string concatenation the compiler expanded into a
     * {@link StringBuilder} chain, and so is already accounted for by the {@code STRING_CONCAT}
     * reported at the chain's {@code new}.
     *
     * <p>Callers use this to leave the chain's own {@code append}/{@code toString} calls alone.
     * Without it a release-8 concatenation reports one allocation plus a trail of unanalyzable
     * calls into the JDK, for what the source writes as a single {@code a + b}.
     */
    public static boolean isStringConcatChainMember(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)
                || !STRING_BUILDER.equals(((MethodInsnNode) insn).owner)) {
            return false;
        }
        for (AbstractInsnNode previous = insn.getPrevious(); previous != null;
                previous = previous.getPrevious()) {
            if (previous.getOpcode() == Opcodes.NEW
                    && STRING_BUILDER.equals(((TypeInsnNode) previous).desc)) {
                Set<AbstractInsnNode> chain = stringConcatChain(previous);
                if (chain != null && chain.contains(insn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The calls belonging to the concatenation chain starting at this {@code new StringBuilder},
     * or null if this allocation is not one.
     *
     * <p>Compiled below release 9 there is no {@code StringConcatFactory} indy: {@code a + b}
     * becomes {@code new StringBuilder()}, a run of {@code append}s and a {@code toString}. Making
     * that a {@code STRING_CONCAT} keeps a category-keyed expectation true whatever release level
     * the code was compiled at.
     *
     * <p>The recognition is deliberately the whole shape, ending at {@code toString}. A builder
     * that escapes instead - stored in a field and reused, returned, passed on - is a hand-written
     * one that happens to be built the same way, and stays a plain {@code NEW}.
     */
    private static Set<AbstractInsnNode> stringConcatChain(AbstractInsnNode allocation) {
        AbstractInsnNode duplicate = nextRealInstruction(allocation);
        if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) {
            return null;
        }
        Set<AbstractInsnNode> members = new HashSet<>();
        boolean constructed = false;
        int appends = 0;
        int depth = 2; // the builder reference and the duplicate the constructor consumes
        for (AbstractInsnNode insn = duplicate.getNext(); insn != null; insn = insn.getNext()) {
            int floor = constructed ? 1 : 2;
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                int popped = callPops(call);
                int remaining = depth - popped;
                if (remaining >= floor) {
                    depth = remaining + Type.getReturnType(call.desc).getSize();
                    continue;
                }
                if (!constructed) {
                    if (remaining != 1 || !isBuilderConstructor(call)) {
                        return null;
                    }
                    constructed = true;
                    members.add(call);
                    depth = 1;
                    continue;
                }
                if (remaining != 0) {
                    return null;
                }
                if (isBuilderAppend(call)) {
                    appends++;
                    members.add(call);
                    depth = 1;
                    continue;
                }
                if (isBuilderToString(call)) {
                    members.add(call);
                    return appends > 0 ? members : null;
                }
                return null;
            }
            int delta = insn instanceof InvokeDynamicInsnNode
                    ? Type.getReturnType(((InvokeDynamicInsnNode) insn).desc).getSize()
                            - argumentSlots(((InvokeDynamicInsnNode) insn).desc)
                    : stackDelta(insn);
            if (delta == UNMODELLED || depth + delta < floor) {
                return null;
            }
            depth += delta;
        }
        return null;
    }

    private static boolean isBuilderConstructor(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESPECIAL
                && STRING_BUILDER.equals(call.owner)
                && "<init>".equals(call.name);
    }

    private static boolean isBuilderAppend(MethodInsnNode call) {
        return STRING_BUILDER.equals(call.owner)
                && "append".equals(call.name)
                && call.desc.endsWith(")Ljava/lang/StringBuilder;");
    }

    private static boolean isBuilderToString(MethodInsnNode call) {
        return STRING_BUILDER.equals(call.owner)
                && "toString".equals(call.name)
                && "()Ljava/lang/String;".equals(call.desc);
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static int callPops(MethodInsnNode call) {
        int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        return argumentSlots(call.desc) + receiver;
    }

    private static int argumentSlots(String descriptor) {
        int slots = 0;
        for (Type parameter : Type.getArgumentTypes(descriptor)) {
            slots += parameter.getSize();
        }
        return slots;
    }

    /**
     * How many stack slots an instruction adds, or {@link #UNMODELLED} for anything that ends
     * straight-line execution (branches, returns, throws) or that this model does not cover.
     * Calls are excluded: their effect depends on the descriptor, and their callers need the pop
     * count separately to see whether a tracked reference is being consumed.
     */
    private static int stackDelta(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode < 0) {
            return 0; // labels, line numbers and frames carry no semantics
        }
        switch (opcode) {
            case Opcodes.NOP:
            case Opcodes.SWAP:
            case Opcodes.IINC:
            case Opcodes.INEG: case Opcodes.LNEG: case Opcodes.FNEG: case Opcodes.DNEG:
            case Opcodes.I2F: case Opcodes.F2I: case Opcodes.I2B: case Opcodes.I2C:
            case Opcodes.I2S: case Opcodes.L2D: case Opcodes.D2L:
            case Opcodes.ARRAYLENGTH: case Opcodes.CHECKCAST: case Opcodes.INSTANCEOF:
            case Opcodes.LALOAD: case Opcodes.DALOAD:
                return 0;
            case Opcodes.ACONST_NULL:
            case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1:
            case Opcodes.ICONST_2: case Opcodes.ICONST_3: case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2:
            case Opcodes.BIPUSH: case Opcodes.SIPUSH:
            case Opcodes.ILOAD: case Opcodes.FLOAD: case Opcodes.ALOAD:
            case Opcodes.DUP: case Opcodes.DUP_X1: case Opcodes.DUP_X2:
            case Opcodes.NEW:
            case Opcodes.I2L: case Opcodes.I2D: case Opcodes.F2L: case Opcodes.F2D:
                return 1;
            case Opcodes.LCONST_0: case Opcodes.LCONST_1:
            case Opcodes.DCONST_0: case Opcodes.DCONST_1:
            case Opcodes.LLOAD: case Opcodes.DLOAD:
            case Opcodes.DUP2: case Opcodes.DUP2_X1: case Opcodes.DUP2_X2:
                return 2;
            case Opcodes.LDC:
                Object constant = ((LdcInsnNode) insn).cst;
                return constant instanceof Long || constant instanceof Double ? 2 : 1;
            case Opcodes.NEWARRAY: case Opcodes.ANEWARRAY:
                return 0;
            case Opcodes.MULTIANEWARRAY:
                return 1 - ((MultiANewArrayInsnNode) insn).dims;
            case Opcodes.IALOAD: case Opcodes.FALOAD: case Opcodes.AALOAD:
            case Opcodes.BALOAD: case Opcodes.CALOAD: case Opcodes.SALOAD:
            case Opcodes.POP:
            case Opcodes.ISTORE: case Opcodes.FSTORE: case Opcodes.ASTORE:
            case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT:
            case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
            case Opcodes.IREM: case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
            case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
            case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV:
            case Opcodes.FREM: case Opcodes.FCMPL: case Opcodes.FCMPG:
            case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
            case Opcodes.L2I: case Opcodes.L2F: case Opcodes.D2I: case Opcodes.D2F:
                return -1;
            case Opcodes.POP2:
            case Opcodes.LSTORE: case Opcodes.DSTORE:
            case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
            case Opcodes.LREM: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
            case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV:
            case Opcodes.DREM:
                return -2;
            case Opcodes.IASTORE: case Opcodes.FASTORE: case Opcodes.AASTORE:
            case Opcodes.BASTORE: case Opcodes.CASTORE: case Opcodes.SASTORE:
            case Opcodes.LCMP: case Opcodes.DCMPL: case Opcodes.DCMPG:
                return -3;
            case Opcodes.LASTORE: case Opcodes.DASTORE:
                return -4;
            case Opcodes.GETSTATIC:
                return fieldSlots(insn);
            case Opcodes.PUTSTATIC:
                return -fieldSlots(insn);
            case Opcodes.GETFIELD:
                return fieldSlots(insn) - 1;
            case Opcodes.PUTFIELD:
                return -fieldSlots(insn) - 1;
            default:
                return UNMODELLED;
        }
    }

    private static int fieldSlots(AbstractInsnNode insn) {
        return Type.getType(((org.objectweb.asm.tree.FieldInsnNode) insn).desc).getSize();
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
        if (OBJECT_METHODS.equals(bootstrapOwner)) {
            // A record's generated toString/equals/hashCode share this bootstrap, and the call
            // site's name is what tells them apart. Only toString allocates: it builds a String
            // from the components on every call. equals and hashCode read them and return a
            // primitive.
            return "toString".equals(indy.name) ? AllocationCategory.RECORD_TO_STRING : null;
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
