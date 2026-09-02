package com.staticallocationchecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Direct unit tests for the shared allocation classifier. The checker and the instrumenter both
 * delegate here, so a gap in this classification is a gap in both.
 */
class AllocationsTest {

    private static final Predicate<String> NOTHING_IS_THROWABLE = name -> false;
    private static final Predicate<String> EVERYTHING_IS_THROWABLE = name -> true;

    private static AllocationCategory categoryOf(AbstractInsnNode insn) {
        return Allocations.categoryOf(insn, NOTHING_IS_THROWABLE);
    }

    @Test
    void classifiesNewAsNew() {
        assertEquals(AllocationCategory.NEW,
                categoryOf(new TypeInsnNode(Opcodes.NEW, "java/lang/Object")));
    }

    @Test
    void exemptsNewOfThrowableTypes() {
        assertNull(Allocations.categoryOf(
                new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"), EVERYTHING_IS_THROWABLE));
    }

    @Test
    void classifiesPrimitiveArrayAllocation() {
        assertEquals(AllocationCategory.NEW_ARRAY,
                categoryOf(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT)));
    }

    @Test
    void classifiesReferenceArrayAllocation() {
        assertEquals(AllocationCategory.NEW_ARRAY,
                categoryOf(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String")));
    }

    @Test
    void classifiesMultiDimensionalArrayAllocation() {
        assertEquals(AllocationCategory.NEW_ARRAY,
                categoryOf(new MultiANewArrayInsnNode("[[I", 2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "java/lang/Integer:(I)Ljava/lang/Integer;",
        "java/lang/Long:(J)Ljava/lang/Long;",
        "java/lang/Short:(S)Ljava/lang/Short;",
        "java/lang/Byte:(B)Ljava/lang/Byte;",
        "java/lang/Character:(C)Ljava/lang/Character;",
        "java/lang/Boolean:(Z)Ljava/lang/Boolean;",
        "java/lang/Float:(F)Ljava/lang/Float;",
        "java/lang/Double:(D)Ljava/lang/Double;",
    })
    void classifiesEveryWrapperValueOfAsBoxing(String ownerAndDescriptor) {
        String[] parts = ownerAndDescriptor.split(":");
        MethodInsnNode call =
                new MethodInsnNode(Opcodes.INVOKESTATIC, parts[0], "valueOf", parts[1], false);

        assertEquals(AllocationCategory.BOXING, categoryOf(call));
    }

    @Test
    void doesNotTreatParsingValueOfOverloadAsBoxing() {
        MethodInsnNode fromString = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(Ljava/lang/String;)Ljava/lang/Integer;", false);

        assertNull(categoryOf(fromString), "valueOf(String) parses, it does not box a primitive");
    }

    @Test
    void doesNotTreatUnrelatedStaticCallAsBoxing() {
        MethodInsnNode parseInt = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);

        assertNull(categoryOf(parseInt));
    }

    @Test
    void doesNotTreatValueOfOnAnUnrelatedOwnerAsBoxing() {
        MethodInsnNode call = new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/util/Optional", "valueOf", "(I)Ljava/util/Optional;", false);

        assertNull(categoryOf(call));
    }

    @Test
    void classifiesStringConcatFactoryIndyAsStringConcat() {
        assertEquals(AllocationCategory.STRING_CONCAT, categoryOf(indy(
                "java/lang/invoke/StringConcatFactory", "(Ljava/lang/String;I)Ljava/lang/String;")));
    }

    @Test
    void classifiesCapturingLambdaMetafactoryIndyAsLambda() {
        assertEquals(AllocationCategory.LAMBDA, categoryOf(indy(
                "java/lang/invoke/LambdaMetafactory", "(Ljava/lang/StringBuilder;)Ljava/lang/Runnable;")));
    }

    @Test
    void exemptsNonCapturingLambdaMetafactoryIndy() {
        assertNull(categoryOf(indy("java/lang/invoke/LambdaMetafactory", "()Ljava/lang/Runnable;")),
                "a lambda capturing nothing links to a cached singleton");
    }

    @Test
    void classifiesARecordsGeneratedToStringAsAnAllocation() {
        assertEquals(AllocationCategory.RECORD_TO_STRING, categoryOf(indy(
                "java/lang/runtime/ObjectMethods", "toString", "(Lcom/example/Point;)Ljava/lang/String;")),
                "a record's toString builds a fresh String on every call");
    }

    @ParameterizedTest
    @ValueSource(strings = {"equals", "hashCode"})
    void exemptsARecordsOtherGeneratedMembers(String name) {
        assertNull(categoryOf(indy(
                        "java/lang/runtime/ObjectMethods", name, "(Lcom/example/Point;)Z")),
                "equals and hashCode share toString's bootstrap but allocate nothing");
    }

    @Test
    void ignoresIndyFromAnUnknownBootstrap() {
        assertNull(categoryOf(indy("com/example/CustomFactory", "()Ljava/lang/Object;")));
    }

    @ParameterizedTest
    @ValueSource(ints = {Opcodes.NOP, Opcodes.POP, Opcodes.DUP, Opcodes.IADD, Opcodes.ARETURN})
    void ignoresNonAllocatingInstructions(int opcode) {
        assertNull(categoryOf(new InsnNode(opcode)));
    }

    @Test
    void ignoresLocalVariableAccess() {
        assertNull(categoryOf(new VarInsnNode(Opcodes.ALOAD, 0)));
    }

    @Test
    void ignoresPlainMethodCalls() {
        assertNull(categoryOf(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)));
    }

    @Test
    void siteKeyJoinsClassMethodLineOffsetAndCategory() {
        assertEquals("com.example.Foo#bar:42@7:NEW",
                Allocations.siteKey("com.example.Foo", "bar", 42, 7, AllocationCategory.NEW));
    }

    @Test
    void siteKeyRepresentsUnknownLineAsMinusOne() {
        assertEquals("com.example.Foo#bar:-1@3:BOXING",
                Allocations.siteKey("com.example.Foo", "bar", -1, 3, AllocationCategory.BOXING));
    }

    @Test
    void siteKeyDistinguishesSitesSharingALine() {
        assertNotEquals(
                Allocations.siteKey("com.example.Foo", "bar", 42, 7, AllocationCategory.NEW),
                Allocations.siteKey("com.example.Foo", "bar", 42, 12, AllocationCategory.NEW),
                "two allocations on one source line are still two sites");
    }

    @Test
    void siteKeyDistinguishesSitesWhenNoDebugInformationExists() {
        assertNotEquals(
                Allocations.siteKey("com.example.Foo", "bar", -1, 7, AllocationCategory.NEW),
                Allocations.siteKey("com.example.Foo", "bar", -1, 12, AllocationCategory.NEW));
    }

    @Test
    void recognisesThrowableSubtypesByReflection() {
        ClassLoader loader = getClass().getClassLoader();

        assertTrue(Allocations.isThrowableByReflection("java/lang/Throwable", loader));
        assertTrue(Allocations.isThrowableByReflection("java/lang/IllegalStateException", loader));
        assertTrue(Allocations.isThrowableByReflection("java/io/IOException", loader));
        assertTrue(Allocations.isThrowableByReflection("java/lang/OutOfMemoryError", loader),
                "Errors are Throwables too");
    }

    @Test
    void rejectsNonThrowablesAndUnloadableNames() {
        ClassLoader loader = getClass().getClassLoader();

        assertFalse(Allocations.isThrowableByReflection("java/lang/Object", loader));
        assertFalse(Allocations.isThrowableByReflection("java/util/ArrayList", loader));
        assertFalse(Allocations.isThrowableByReflection("com/example/DoesNotExist", loader),
                "an unresolvable name is not exempt");
    }

    @Test
    void reflectiveThrowableCheckDoesNotInitialiseTheClass() {
        assertTrue(Allocations.isThrowableByReflection(
                        ExplodesOnInitialisation.class.getName().replace('.', '/'), getClass().getClassLoader()),
                "resolution must use initialize=false, or the static initialiser would throw");
    }

    /** Its static initialiser fails, so any test touching it proves the class was initialised. */
    static class ExplodesOnInitialisation extends IOException {
        static {
            if (Boolean.parseBoolean("true")) {
                throw new AssertionError("class must not be initialised by the Throwable check");
            }
        }
    }

    private static InvokeDynamicInsnNode indy(String bootstrapOwner, String descriptor) {
        return indy(bootstrapOwner, "run", descriptor);
    }

    private static InvokeDynamicInsnNode indy(String bootstrapOwner, String name, String descriptor) {
        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC, bootstrapOwner, "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        return new InvokeDynamicInsnNode(name, descriptor, bootstrap);
    }
}
