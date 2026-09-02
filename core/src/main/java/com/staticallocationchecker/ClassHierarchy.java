package com.staticallocationchecker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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

    /** Supertype internal name to the types that directly extend or implement it. */
    private final Map<String, List<String>> directSubtypes;

    ClassHierarchy(Map<String, ClassNode> index) {
        this.index = index;
        this.directSubtypes = indexDirectSubtypes(index);
    }

    private static Map<String, List<String>> indexDirectSubtypes(Map<String, ClassNode> index) {
        Map<String, List<String>> subtypes = new HashMap<>();
        for (ClassNode node : index.values()) {
            if (node.superName != null) {
                subtypes.computeIfAbsent(node.superName, key -> new ArrayList<>()).add(node.name);
            }
            for (String interfaceName : node.interfaces) {
                subtypes.computeIfAbsent(interfaceName, key -> new ArrayList<>()).add(node.name);
            }
        }
        return subtypes;
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
            if (!(o instanceof MethodRef)) {
                return false;
            }
            MethodRef other = (MethodRef) o;
            return owner.name.equals(other.owner.name)
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
        return Collections.unmodifiableList(new ArrayList<>(targets));
    }

    /**
     * Whether a strict supertype declares this method carrying the given annotation.
     *
     * <p>Java does not inherit annotations onto an override, so without this an override silently
     * drops whatever contract its supertype declared - the easiest way for a codebase to lose
     * coverage without anyone noticing.
     */
    boolean inheritsAnnotation(String owner, String name, String descriptor, String annotationDescriptor) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        ClassNode start = index.get(owner);
        if (start == null) {
            return false;
        }
        enqueueSupertypes(start, pending);
        while (!pending.isEmpty()) {
            String supertype = pending.poll();
            if (!visited.add(supertype)) {
                continue;
            }
            ClassNode node = index.get(supertype);
            if (node == null) {
                continue;
            }
            MethodNode declared = findDeclared(node, name, descriptor);
            if (declared != null && hasAnnotation(declared, annotationDescriptor)) {
                return true;
            }
            enqueueSupertypes(node, pending);
        }
        return false;
    }

    private static void enqueueSupertypes(ClassNode node, Deque<String> pending) {
        if (node.superName != null) {
            pending.add(node.superName);
        }
        pending.addAll(node.interfaces);
    }

    private static boolean hasAnnotation(MethodNode method, String annotationDescriptor) {
        return method.visibleAnnotations != null
                && method.visibleAnnotations.stream()
                        .anyMatch(a -> annotationDescriptor.equals(a.desc));
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
        for (String subtype : descendantsOf(owner)) {
            ClassNode candidate = index.get(subtype);
            if (candidate == null) {
                continue;
            }
            MethodNode declared = findDeclared(candidate, name, descriptor);
            if (declared != null && hasBody(declared)) {
                overrides.add(new MethodRef(candidate, declared));
            }
        }
        return overrides;
    }

    /**
     * Every indexed type below {@code supertype}, walked through the precomputed subtype map.
     *
     * <p>Scanning the whole index per call site was fine when the index held only the code under
     * analysis. A resolve classpath can add a project's entire dependency graph, so the direct
     * subtypes are indexed once up front and this walks down from the type actually asked about.
     */
    private Set<String> descendantsOf(String supertype) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(directSubtypes.getOrDefault(supertype, Collections.emptyList()));
        while (!pending.isEmpty()) {
            String subtype = pending.poll();
            if (found.add(subtype)) {
                pending.addAll(directSubtypes.getOrDefault(subtype, Collections.emptyList()));
            }
        }
        return found;
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
