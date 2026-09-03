package com.staticallocationchecker;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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

    /** Keyed by identity: each analyse run builds one index and wants one oracle for it. */
    private final Map<Map<String, ClassNode>, TypeOracle> oracles = new IdentityHashMap<>();

    private final int targetRelease;

    /**
     * A checker that reads only the base entries of any multi-release jar it is pointed at.
     *
     * <p>That is the conservative default rather than the right answer for everybody: it is what
     * this checker has always done, and it matches a JVM at release 8. Say which release the code
     * will run on - {@link #AllocationChecker(int)} - and the versioned entries come into view.
     */
    public AllocationChecker() {
        this(0);
    }

    /**
     * A checker that resolves multi-release jars (JEP 238) as a JVM at the given release would.
     *
     * <p>A dependency shipped as a multi-release jar carries the same class twice or more, and the
     * copies are not equivalent - the modern one exists precisely because it does something
     * differently. Analysing whichever copy happened to be indexed first would report on code the
     * application never runs, so the release the application runs on is part of the question.
     *
     * @param targetRelease the Java release the analysed code will run on, as a plain feature
     *                      number ({@code 8}, {@code 17}, {@code 21}). {@code 0} - or any value
     *                      below 9 - means base entries only, which is the no-argument
     *                      constructor's behaviour and the behaviour of every release before this
     *                      parameter existed. Directories are unaffected either way: only jars can
     *                      be multi-release.
     */
    public AllocationChecker(int targetRelease) {
        this.targetRelease = targetRelease;
    }

    /**
     * Analyses the given roots.
     *
     * @param analysisRoots    directories or jar/zip archives of {@code .class} files to analyse;
     *                         annotated entry points are discovered by scanning these
     * @param resolveClasspath additional directories or archives used only to resolve callees.
     *                         Classes here are followed when a hot path reaches them, but are
     *                         never scanned for annotated entry points - they are somebody else's
     *                         code, and their contracts are not this build's business.
     * @return the findings
     */
    public Report analyze(List<Path> analysisRoots, List<Path> resolveClasspath) {
        return analyze(analysisRoots, resolveClasspath, Collections.emptyList());
    }

    /**
     * Analyses starting from named entry points rather than from annotated methods.
     *
     * <p>Annotations are the right way to state a contract in code you own, but they are not always
     * available or appropriate: generated code, a dependency you cannot edit, or simply asking
     * "what does this one method allocate?" without committing the answer to a source file. Naming
     * the starting point covers those.
     *
     * <p>When {@code entryPoints} is non-empty, discovery by annotation is skipped entirely and
     * only the named methods are walked. That is deliberate - the point of naming a starting point
     * is to analyse <em>that</em>, not that plus whatever else happens to be annotated nearby.
     * Warmup methods still act as boundaries wherever the walk reaches one.
     *
     * @param entryPoints methods to start from, as {@code binary.ClassName},
     *                    {@code binary.ClassName#method}, or {@code binary.ClassName#method(desc)}
     *                    for an exact overload
     */
    public Report analyze(
            List<Path> analysisRoots, List<Path> resolveClasspath, List<String> entryPoints) {
        Map<String, ClassNode> analysisIndex = buildIndex(analysisRoots);

        // Resolution sees both, with the analysis roots winning any clash: if the same class is on
        // both, the copy being analysed is the one that matters.
        Map<String, ClassNode> index = new HashMap<>(buildIndex(resolveClasspath));
        index.putAll(analysisIndex);

        ClassHierarchy hierarchy = new ClassHierarchy(index);
        List<Finding> findings = new ArrayList<>();

        if (!entryPoints.isEmpty()) {
            walkNamedEntryPoints(entryPoints, analysisIndex, index, hierarchy, findings);
            return new Report(findings);
        }

        for (ClassNode classNode : analysisIndex.values()) {
            boolean typeLevel = hasAnnotation(classNode.visibleAnnotations, ZERO_ALLOCATIONS);
            for (MethodNode method : classNode.methods) {
                boolean zeroAllocations =
                        typeLevel || hasAnnotation(method.visibleAnnotations, ZERO_ALLOCATIONS);
                if (declaresBothContracts(method)) {
                    // Both annotations on one declaration is a contradiction. Resolving it by
                    // precedence would silently discard whichever contract lost, so say so instead.
                    findings.add(new Finding(
                            Finding.Kind.CONFLICTING_CONTRACTS,
                            Type.getObjectType(classNode.name).getClassName(),
                            method.name, method.desc, -1, null,
                            Collections.singletonList(signature(classNode, method))));
                } else if (isWarmup(classNode, method)) {
                    analyzeWarmupMethod(classNode, method, index, findings);
                } else if (zeroAllocations) {
                    walkEntry(classNode, method, index, hierarchy, findings);
                } else if (inherits(hierarchy, classNode, method, ALLOCATIONS_FOR_WARMUP)) {
                    analyzeWarmupMethod(classNode, method, index, findings);
                } else if (inherits(hierarchy, classNode, method, ZERO_ALLOCATIONS)) {
                    walkEntry(classNode, method, index, hierarchy, findings);
                }
            }
        }
        return new Report(findings);
    }

    /**
     * Walks every method matching a named entry point.
     *
     * <p>An entry point that matches nothing is an error rather than an empty result. A typo in a
     * class name would otherwise produce a clean report for code that was never looked at, which is
     * the failure this tool exists to prevent - and it would look exactly like success.
     */
    private void walkNamedEntryPoints(
            List<String> entryPoints,
            Map<String, ClassNode> analysisIndex,
            Map<String, ClassNode> index,
            ClassHierarchy hierarchy,
            List<Finding> findings) {
        for (String spec : entryPoints) {
            EntryPointSpec parsed = EntryPointSpec.parse(spec);
            int matched = 0;
            for (ClassNode classNode : analysisIndex.values()) {
                if (!parsed.matchesClass(classNode)) {
                    continue;
                }
                for (MethodNode method : classNode.methods) {
                    if (!parsed.matchesMethod(method)) {
                        continue;
                    }
                    matched++;
                    if (isWarmup(classNode, method)) {
                        analyzeWarmupMethod(classNode, method, index, findings);
                    } else {
                        walkEntry(classNode, method, index, hierarchy, findings);
                    }
                }
            }
            if (matched == 0) {
                throw new IllegalArgumentException(
                        "Entry point '" + spec + "' matched nothing in the analysis roots. "
                                + "Reporting no findings for it would be indistinguishable from "
                                + "having checked it and found none.");
            }
        }
    }

    /** A parsed {@code Class}, {@code Class#method} or {@code Class#method(descriptor)} selector. */
    private static final class EntryPointSpec {
        private final String internalClassName;
        private final String methodName;
        private final String descriptor;

        private EntryPointSpec(String internalClassName, String methodName, String descriptor) {
            this.internalClassName = internalClassName;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        static EntryPointSpec parse(String spec) {
            String trimmed = spec.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Empty entry point specification");
            }
            int hash = trimmed.indexOf('#');
            if (hash < 0) {
                return new EntryPointSpec(trimmed.replace('.', '/'), null, null);
            }
            String className = trimmed.substring(0, hash).replace('.', '/');
            String member = trimmed.substring(hash + 1);
            int paren = member.indexOf('(');
            if (paren < 0) {
                return new EntryPointSpec(className, member, null);
            }
            return new EntryPointSpec(
                    className, member.substring(0, paren), member.substring(paren));
        }

        boolean matchesClass(ClassNode classNode) {
            return classNode.name.equals(internalClassName);
        }

        boolean matchesMethod(MethodNode method) {
            if (methodName != null && !method.name.equals(methodName)) {
                return false;
            }
            return descriptor == null || method.desc.equals(descriptor);
        }
    }

    /** Walks a single annotated entry point and everything it calls transitively. */
    private void walkEntry(
            ClassNode owner,
            MethodNode entry,
            Map<String, ClassNode> index,
            ClassHierarchy hierarchy,
            List<Finding> findings) {
        walk(owner, entry, Collections.singletonList(signature(owner, entry)), new HashSet<>(), index, hierarchy, findings);
    }

    private void walk(
            ClassNode owner,
            MethodNode method,
            List<String> callPath,
            Set<String> visited,
            Map<String, ClassNode> index,
            ClassHierarchy hierarchy,
            List<Finding> findings) {
        if (!visited.add(key(owner, method))) {
            return;
        }
        String className = Type.getObjectType(owner.name).getClassName();
        int line = -1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode) {
                line = ((LineNumberNode) insn).line;
                continue;
            }
            AllocationCategory category = siteCategory(insn, index);
            if (category != null) {
                findings.add(new Finding(
                        Finding.Kind.ZERO_ALLOCATION_VIOLATION,
                        className, method.name, method.desc, line, category, callPath));
            }
            // Only a plain (non-allocating) call is a candidate for descent.
            //
            // Constructor calls are deliberately not followed. The target is perfectly resolvable -
            // INVOKESPECIAL <init> is statically bound, so there is no ambiguity to stop us - but
            // descending would add noise without adding detection. On a zero-allocation path the
            // NEW at this call site is already a violation, so the site is flagged and the fix is
            // the same whether its constructor allocates once or twenty times. Reached from a
            // warmup boundary, construction is sanctioned by design. Following it would instead
            // report several findings per fix, and drag in a spurious unanalyzable call for the
            // java.lang.Object.<init> that terminates every constructor chain.
            //
            // What a constructor allocates is still analysable: annotate its type or the
            // constructor itself, or name it as an entry point.
            // The append/toString calls of a string concatenation the compiler expanded into a
            // StringBuilder chain are skipped too: the chain's allocation is already reported at
            // its NEW, and descending into the JDK from there produces a trail of unanalyzable
            // calls for what the source writes as a single `a + b`.
            if (category == null && insn instanceof MethodInsnNode
                    && !((MethodInsnNode) insn).name.equals("<init>")
                    && !Allocations.isStringConcatChainMember(insn)) {
                MethodInsnNode call = (MethodInsnNode) insn;
                // A call site may reach more than one body: the declaration it names, plus every
                // indexed override reachable by virtual dispatch. All of them are on the hot path.
                List<ClassHierarchy.MethodRef> targets =
                        hierarchy.resolve(call.getOpcode(), call.owner, call.name, call.desc);
                if (targets.isEmpty()) {
                    List<String> unresolvedPath = new ArrayList<>(callPath);
                    unresolvedPath.add(targetSignature(call));
                    findings.add(new Finding(
                            Finding.Kind.UNANALYZABLE_CALL,
                            className, method.name, method.desc, line, null, unresolvedPath));
                    continue;
                }
                for (ClassHierarchy.MethodRef target : targets) {
                    if (isWarmup(target.owner(), target.method())) {
                        continue; // warmup boundary: its allocations are sanctioned, stop descending
                    }
                    List<String> nextPath = new ArrayList<>(callPath);
                    nextPath.add(signature(target.owner(), target.method()));
                    walk(target.owner(), target.method(), nextPath, visited, index, hierarchy, findings);
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
            Analyzer<SourceValue> analyzer = new Analyzer<SourceValue>(originPreserving) {
                @Override
                protected void newControlFlowEdge(int from, int to) {
                    successors.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                }
            };
            frames = analyzer.analyze(owner.name, method);
        } catch (AnalyzerException e) {
            // Returning quietly would make an unanalysable warmup method indistinguishable from a
            // compliant one. Say so instead, so the gap in coverage is visible in the report.
            findings.add(new Finding(
                    Finding.Kind.UNANALYZABLE_CALL,
                    Type.getObjectType(owner.name).getClassName(),
                    method.name,
                    method.desc,
                    -1,
                    null,
                    Collections.singletonList(signature(owner, method))));
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
            if (frames[i] != null) {
                collectRetained(insn, frames[i], cachedProducers);
            }
        }

        String className = Type.getObjectType(owner.name).getClassName();
        List<String> path = Collections.singletonList(signature(owner, method));
        int line = -1;
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof LineNumberNode) {
                line = ((LineNumberNode) insn).line;
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

    /**
     * Records the instructions whose produced reference this one retains.
     *
     * <p>Warmup code caches an object in more ways than a direct field store: it fills a pooled
     * array, or hands the object to a collection or map already held in a field. All of those keep
     * the object alive past the method, which is what the contract is really asking about, so all
     * of them count as cached.
     */
    private static void collectRetained(
            AbstractInsnNode insn, Frame<SourceValue> frame, Set<AbstractInsnNode> retained) {
        int opcode = insn.getOpcode();

        // The reference is stored straight into a field.
        if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
            retained.addAll(frame.getStack(frame.getStackSize() - 1).insns);
            return;
        }

        // The reference becomes an element of an array. The array itself must still justify
        // itself separately - it is an allocation too, and gets its own verdict.
        if (opcode == Opcodes.AASTORE) {
            retained.addAll(frame.getStack(frame.getStackSize() - 1).insns);
            return;
        }

        // The reference is handed to something already reachable from a field: list.add(x),
        // map.put(k, x), pool.offer(x). The receiver being a field read is what distinguishes
        // "stored into the object graph" from "passed to a temporary and dropped".
        if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)
                && insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            int argumentCount = Type.getArgumentTypes(call.desc).length;
            int receiverIndex = frame.getStackSize() - argumentCount - 1;
            if (receiverIndex < 0 || !isReadFromAField(frame.getStack(receiverIndex))) {
                return;
            }
            for (int argument = 0; argument < argumentCount; argument++) {
                retained.addAll(frame.getStack(frame.getStackSize() - 1 - argument).insns);
            }
        }
    }

    /** Whether every instruction that could have produced this value is a field read. */
    private static boolean isReadFromAField(SourceValue value) {
        if (value.insns.isEmpty()) {
            return false;
        }
        return value.insns.stream().allMatch(insn ->
                insn.getOpcode() == Opcodes.GETFIELD || insn.getOpcode() == Opcodes.GETSTATIC);
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
            for (int next : successors.getOrDefault(node, Collections.<Integer>emptyList())) {
                if (next != avoid && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * Allocation-site classification, resolved through the shared {@link TypeOracle}.
     *
     * <p>The checker and the runtime instrumenter previously each carried their own hierarchy walk
     * and their own fallbacks, so the same code could be classified one way at build time and
     * another at runtime. Both now go through the same algorithm; only the reachable class data
     * differs, which is a real difference in what is knowable rather than one in policy.
     */
    private AllocationCategory siteCategory(AbstractInsnNode insn, Map<String, ClassNode> index) {
        return oracleFor(index).categoryOf(insn);
    }

    /** One oracle per index, so its supertype walks are cached across every site in a run. */
    private TypeOracle oracleFor(Map<String, ClassNode> index) {
        return oracles.computeIfAbsent(
                index, key -> TypeOracle.forIndex(key, getClass().getClassLoader()));
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

    /**
     * Whether one declaration carries both annotations.
     *
     * <p>Deliberately checks the method's own annotations only. A type-level annotation of one kind
     * with a method-level annotation of the other is a normal, useful thing to write - a warmup
     * method inside a zero-allocation class - where the more specific declaration simply wins.
     */
    private static boolean declaresBothContracts(MethodNode method) {
        return hasAnnotation(method.visibleAnnotations, ZERO_ALLOCATIONS)
                && hasAnnotation(method.visibleAnnotations, ALLOCATIONS_FOR_WARMUP);
    }

    /**
     * Whether a supertype declares this method under the given contract.
     *
     * <p>Only consulted when neither the method nor its type declares a contract of its own, so a
     * declaration always beats an inherited one and this can never override an explicit choice.
     */
    private static boolean inherits(
            ClassHierarchy hierarchy, ClassNode owner, MethodNode method, String annotation) {
        if (method.name.startsWith("<")) {
            return false; // constructors and initialisers are not overrides
        }
        return hierarchy.inheritsAnnotation(owner.name, method.name, method.desc, annotation);
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
            if (isArchive(root)) {
                indexArchive(root, index);
            } else {
                indexDirectory(root, index);
            }
        }
        return index;
    }

    private static boolean isArchive(Path root) {
        if (!Files.isRegularFile(root)) {
            return false;
        }
        String name = root.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private void indexDirectory(Path root, Map<String, ClassNode> index) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                ClassNode node = readClass(p);
                index.putIfAbsent(node.name, node);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    /**
     * Indexes the classes inside a jar. Silently indexing nothing here would mean reporting a clean
     * bill of health for code that was never read, so anything unreadable fails loudly instead.
     *
     * <p>Which entry a class comes from is {@link MultiReleaseJar}'s decision: a multi-release jar
     * holds several copies of one class, and only one of them is the copy that runs.
     */
    private void indexArchive(Path archive, Map<String, ClassNode> index) {
        try (JarFile jar = new JarFile(archive.toFile())) {
            for (JarEntry entry : MultiReleaseJar.classEntries(jar, targetRelease).values()) {
                try (InputStream in = jar.getInputStream(entry)) {
                    ClassNode node = readClass(Streams.readAllBytes(in), archive + "!" + entry.getName());
                    index.putIfAbsent(node.name, node);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + archive, e);
        }
    }

    private ClassNode readClass(Path classFile) {
        try {
            return readClass(Files.readAllBytes(classFile), classFile.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + classFile, e);
        }
    }

    private ClassNode readClass(byte[] classBytes, String description) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            return node;
        } catch (RuntimeException e) {
            if (BytecodeSupport.isUnsupportedVersion(e)) {
                // Worth its own message: "Failed to parse Foo.class" reads as "your class file is
                // corrupt", and sends the user hunting a bug that is not theirs. The real story is
                // that the checker is older than the compiler, and only an upgrade fixes it.
                throw new IllegalStateException(
                        BytecodeSupport.tooNewMessage(description, BytecodeSupport.majorVersionOf(classBytes)),
                        e);
            }
            throw new IllegalStateException("Failed to parse " + description, e);
        }
    }
}
