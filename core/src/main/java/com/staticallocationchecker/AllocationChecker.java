package com.staticallocationchecker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Analyses compiled bytecode for allocation violations against the
 * {@link com.staticallocationchecker.annotations.ZeroAllocations} and
 * {@link com.staticallocationchecker.annotations.AllocationsForWarmup} contracts.
 */
public final class AllocationChecker {

    private static final String ZERO_ALLOCATIONS =
            "Lcom/staticallocationchecker/annotations/ZeroAllocations;";
    private static final String ALLOCATIONS_FOR_WARMUP =
            "Lcom/staticallocationchecker/annotations/AllocationsForWarmup;";

    /**
     * Analyses the given roots.
     *
     * @param analysisRoots    directories (or jars) of {@code .class} files to analyse; annotated
     *                         entry points are discovered by scanning these
     * @param resolveClasspath additional classpath used only to resolve callees (unused for now)
     * @return the findings
     */
    public Report analyze(List<Path> analysisRoots, List<Path> resolveClasspath) {
        Map<String, ClassNode> index = buildIndex(analysisRoots);
        List<Finding> findings = new ArrayList<>();
        for (ClassNode classNode : index.values()) {
            boolean typeLevel = hasAnnotation(classNode.visibleAnnotations, ZERO_ALLOCATIONS);
            for (MethodNode method : classNode.methods) {
                if (isWarmup(classNode, method)) {
                    analyzeWarmupMethod(classNode, method, index, findings);
                } else if (typeLevel || hasAnnotation(method.visibleAnnotations, ZERO_ALLOCATIONS)) {
                    walkEntry(classNode, method, index, findings);
                }
            }
        }
        return new Report(findings);
    }

    /** Walks a single annotated entry point and everything it calls transitively. */
    private void walkEntry(ClassNode owner, MethodNode entry, Map<String, ClassNode> index, List<Finding> findings) {
        walk(owner, entry, List.of(signature(owner, entry)), new HashSet<>(), index, findings);
    }

    private void walk(
            ClassNode owner,
            MethodNode method,
            List<String> callPath,
            Set<String> visited,
            Map<String, ClassNode> index,
            List<Finding> findings) {
        if (!visited.add(key(owner, method))) {
            return;
        }
        String className = Type.getObjectType(owner.name).getClassName();
        int line = -1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            AllocationCategory category = siteCategory(insn, index);
            if (category != null) {
                findings.add(new Finding(
                        Finding.Kind.ZERO_ALLOCATION_VIOLATION,
                        className, method.name, method.desc, line, category, callPath));
            }
            // Only a plain (non-allocating) call is a candidate for descent. Constructor calls
            // (<init>) are construction, already represented by the paired allocation opcode.
            if (category == null && insn instanceof MethodInsnNode call && !call.name.equals("<init>")) {
                ClassNode calleeOwner = index.get(call.owner);
                MethodNode callee = findMethod(calleeOwner, call.name, call.desc);
                if (callee != null) {
                    if (isWarmup(calleeOwner, callee)) {
                        continue; // warmup boundary: its allocations are sanctioned, stop descending
                    }
                    List<String> nextPath = new ArrayList<>(callPath);
                    nextPath.add(signature(calleeOwner, callee));
                    walk(calleeOwner, callee, nextPath, visited, index, findings);
                } else {
                    List<String> unresolvedPath = new ArrayList<>(callPath);
                    unresolvedPath.add(targetSignature(call));
                    findings.add(new Finding(
                            Finding.Kind.UNANALYZABLE_CALL,
                            className, method.name, method.desc, line, null, unresolvedPath));
                }
            }
        }
    }

    /**
     * Verifies the {@link com.staticallocationchecker.annotations.AllocationsForWarmup} contract:
     * every allocation in the method must be guarded (control-dependent on a branch) and cached
     * (its reference flows into a field store).
     */
    private void analyzeWarmupMethod(
            ClassNode owner, MethodNode method, Map<String, ClassNode> index, List<Finding> findings) {
        InsnList instructions = method.instructions;
        if (instructions.size() == 0) {
            return;
        }

        Map<Integer, List<Integer>> successors = new HashMap<>();
        Frame<SourceValue>[] frames;
        try {
            // A source interpreter that preserves the originating instruction across copies
            // (DUP, local load/store), so an allocation's reference can be traced to a field
            // store even when it hops through a local variable.
            SourceInterpreter originPreserving = new SourceInterpreter(Opcodes.ASM9) {
                @Override
                public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
                    return value;
                }
            };
            Analyzer<SourceValue> analyzer = new Analyzer<>(originPreserving) {
                @Override
                protected void newControlFlowEdge(int from, int to) {
                    successors.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                }
            };
            frames = analyzer.analyze(owner.name, method);
        } catch (AnalyzerException e) {
            return;
        }

        Set<Integer> exits = new HashSet<>();
        Set<AbstractInsnNode> cachedProducers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            int opcode = insn.getOpcode();
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
                exits.add(i);
            }
            if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && frames[i] != null) {
                Frame<SourceValue> frame = frames[i];
                cachedProducers.addAll(frame.getStack(frame.getStackSize() - 1).insns);
            }
        }

        String className = Type.getObjectType(owner.name).getClassName();
        List<String> path = List.of(signature(owner, method));
        int line = -1;
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof LineNumberNode lineNode) {
                line = lineNode.line;
                continue;
            }
            AllocationCategory category = siteCategory(insn, index);
            if (category == null) {
                continue;
            }
            if (!exitReachableAvoiding(i, successors, exits)) {
                findings.add(new Finding(
                        Finding.Kind.WARMUP_NOT_GUARDED, className, method.name, method.desc, line, category, path));
            } else if (!cachedProducers.contains(insn)) {
                findings.add(new Finding(
                        Finding.Kind.WARMUP_NOT_CACHED, className, method.name, method.desc, line, category, path));
            }
        }
    }

    /** Whether some method exit is reachable from entry without passing through {@code avoid}. */
    private static boolean exitReachableAvoiding(int avoid, Map<Integer, List<Integer>> successors, Set<Integer> exits) {
        if (avoid == 0) {
            return false;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(0);
        seen.add(0);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            if (exits.contains(node)) {
                return true;
            }
            for (int next : successors.getOrDefault(node, List.of())) {
                if (next != avoid && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /** Unified allocation-site classifier that exempts Throwable allocations resolvable via {@code index}. */
    private AllocationCategory siteCategory(AbstractInsnNode insn, Map<String, ClassNode> index) {
        return Allocations.categoryOf(insn, name -> isThrowable(name, index));
    }

    /** Whether {@code internalName} resolves to a subtype of {@link Throwable}. */
    private boolean isThrowable(String internalName, Map<String, ClassNode> index) {
        String current = internalName;
        while (current != null) {
            if (current.equals("java/lang/Throwable")) {
                return true;
            }
            if (current.equals("java/lang/Object")) {
                return false;
            }
            ClassNode node = index.get(current);
            if (node != null) {
                current = node.superName;
            } else {
                return Allocations.isThrowableByReflection(current, getClass().getClassLoader());
            }
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        if (owner == null) {
            return null;
        }
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        return null;
    }

    private static String signature(ClassNode owner, MethodNode method) {
        return Type.getObjectType(owner.name).getClassName() + "#" + method.name + method.desc;
    }

    private static String targetSignature(MethodInsnNode call) {
        return Type.getObjectType(call.owner).getClassName() + "#" + call.name + call.desc;
    }

    private static String key(ClassNode owner, MethodNode method) {
        return owner.name + "#" + method.name + method.desc;
    }

    private static boolean isWarmup(ClassNode owner, MethodNode method) {
        return hasAnnotation(method.visibleAnnotations, ALLOCATIONS_FOR_WARMUP)
                || hasAnnotation(owner.visibleAnnotations, ALLOCATIONS_FOR_WARMUP);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return false;
        }
        return annotations.stream().anyMatch(a -> descriptor.equals(a.desc));
    }

    private Map<String, ClassNode> buildIndex(List<Path> roots) {
        Map<String, ClassNode> index = new HashMap<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    ClassNode node = readClass(p);
                    index.putIfAbsent(node.name, node);
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + root, e);
            }
        }
        return index;
    }

    private ClassNode readClass(Path classFile) {
        try {
            ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + classFile, e);
        }
    }
}
