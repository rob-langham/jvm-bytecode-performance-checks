package com.staticallocationchecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Resolves a call site to the bytecode that can actually run at it.
 *
 * <p>A call site names a declaring type, but the method that executes may be declared in a
 * supertype (inheritance) or in any subtype that overrides it (virtual dispatch). Resolving only
 * against the named type misses both, and for an abstract or interface method it "resolves" to a
 * declaration with no body - which reads as "this method allocates nothing" and is the most
 * dangerous possible answer for a checker to give.
 *
 * <p>Resolution is limited to the analysis roots. An override that is not indexed cannot be found,
 * which is a limit of the input rather than of this class; callers report that as an unanalyzable
 * call rather than treating it as clean.
 */
final class ClassHierarchy {

    private final Map<String, ClassNode> index;
    private final Map<String, List<MethodRef>> resolutionCache = new HashMap<>();

    ClassHierarchy(Map<String, ClassNode> index) {
        this.index = index;
    }

    /** A method together with the class that declares it. */
    static final class MethodRef {
        private final ClassNode owner;
        private final MethodNode method;

        MethodRef(ClassNode owner, MethodNode method) {
            this.owner = owner;
            this.method = method;
        }

        ClassNode owner() {
            return owner;
        }

        MethodNode method() {
            return method;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MethodRef other
                    && owner.name.equals(other.owner.name)
                    && method.name.equals(other.method.name)
                    && method.desc.equals(other.method.desc);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(owner.name, method.name, method.desc);
        }
    }

    /**
     * Every method body that could execute at a call site, within the analysis roots.
     *
     * @param opcode     the invoke opcode, which decides whether dispatch is virtual
     * @param owner      internal name of the type named at the call site
     * @param name       method name
     * @param descriptor method descriptor
     * @return the possible targets; empty when nothing could be resolved
     */
    List<MethodRef> resolve(int opcode, String owner, String name, String descriptor) {
        String key = opcode + "|" + owner + "|" + name + "|" + descriptor;
        List<MethodRef> cached = resolutionCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<MethodRef> resolved = computeResolve(opcode, owner, name, descriptor);
        resolutionCache.put(key, resolved);
        return resolved;
    }

    private List<MethodRef> computeResolve(int opcode, String owner, String name, String descriptor) {
        // Set semantics: a subtype that inherits rather than overrides resolves to the same
        // MethodRef as its parent, and should be walked once.
        Set<MethodRef> targets = new LinkedHashSet<>();

        MethodRef declared = declaredMethod(owner, name, descriptor);
        if (declared != null && hasBody(declared.method())) {
            targets.add(declared);
        }

        // INVOKESTATIC and INVOKESPECIAL (constructors, private methods, super calls) are not
        // dispatched dynamically, so the declaration is the whole answer.
        boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
        if (virtual) {
            targets.addAll(overridesOf(owner, name, descriptor));
        }
        return List.copyOf(targets);
    }

    /** The method a call site resolves to by declaration, climbing superclasses then interfaces. */
    private MethodRef declaredMethod(String owner, String name, String descriptor) {
        ClassNode current = index.get(owner);
        while (current != null) {
            MethodNode declared = findDeclared(current, name, descriptor);
            if (declared != null) {
                return new MethodRef(current, declared);
            }
            current = current.superName == null ? null : index.get(current.superName);
        }
        // Default methods are declared on an interface, which is not on the superclass chain.
        return interfaceMethod(owner, name, descriptor, new LinkedHashSet<>());
    }

    private MethodRef interfaceMethod(
            String owner, String name, String descriptor, Set<String> visited) {
        ClassNode node = index.get(owner);
        if (node == null || !visited.add(owner)) {
            return null;
        }
        for (String interfaceName : node.interfaces) {
            ClassNode interfaceNode = index.get(interfaceName);
            if (interfaceNode != null) {
                MethodNode declared = findDeclared(interfaceNode, name, descriptor);
                if (declared != null) {
                    return new MethodRef(interfaceNode, declared);
                }
            }
            MethodRef inherited = interfaceMethod(interfaceName, name, descriptor, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return node.superName == null ? null : interfaceMethod(node.superName, name, descriptor, visited);
    }

    /** Every indexed subtype of {@code owner} that declares a body for this method. */
    private List<MethodRef> overridesOf(String owner, String name, String descriptor) {
        List<MethodRef> overrides = new ArrayList<>();
        for (ClassNode candidate : index.values()) {
            if (candidate.name.equals(owner) || !isSubtypeOf(candidate.name, owner, new LinkedHashSet<>())) {
                continue;
            }
            MethodNode declared = findDeclared(candidate, name, descriptor);
            if (declared != null && hasBody(declared)) {
                overrides.add(new MethodRef(candidate, declared));
            }
        }
        return overrides;
    }

    private boolean isSubtypeOf(String candidate, String supertype, Set<String> visited) {
        if (candidate == null || !visited.add(candidate)) {
            return false;
        }
        if (candidate.equals(supertype)) {
            return true;
        }
        ClassNode node = index.get(candidate);
        if (node == null) {
            return false;
        }
        if (isSubtypeOf(node.superName, supertype, visited)) {
            return true;
        }
        for (String interfaceName : node.interfaces) {
            if (isSubtypeOf(interfaceName, supertype, visited)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findDeclared(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        return null;
    }

    /** Abstract and native methods have no bytecode to walk. */
    private static boolean hasBody(MethodNode method) {
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }
}
