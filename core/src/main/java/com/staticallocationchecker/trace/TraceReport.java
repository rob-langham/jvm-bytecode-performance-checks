package com.staticallocationchecker.trace;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.Report;
import com.staticallocationchecker.runtime.AllocationTraceReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Turns a trace written by the agent into a report, and ties each site back to the bytecode that
 * made it.
 *
 * <p>For every site the report gives the count, when it first and last fired, and whether it is
 * <em>steady</em>: a site that makes no allocations in the final part of the run has settled. It
 * then reads the classes the site belongs to and says what contract governs it - an
 * {@code @AllocationsForWarmup} method, or a synthetic lambda body reached from one - and how many
 * static findings the checker reports against that method.
 *
 * <p>Usage: {@code TraceReport <trace-directory> [classes-directory...]}. The class directories are
 * where the annotated bytecode lives; without them the report still shows the trace, but cannot say
 * anything about the contracts.
 */
public final class TraceReport {

    /** A site is steady when its last allocation falls before this share of the run. */
    static final double STEADY_BEFORE = 0.8;

    private static final String WARMUP = "Lcom/staticallocationchecker/annotations/AllocationsForWarmup;";

    private TraceReport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: TraceReport <trace-directory> [classes-directory...]");
            System.exit(2);
        }
        List<Path> classRoots = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            classRoots.add(Paths.get(args[i]));
        }
        System.out.print(render(Paths.get(args[0]), classRoots));
    }

    /** The full text of the report for the trace in {@code traceDirectory}. */
    public static String render(Path traceDirectory, List<Path> classRoots) throws IOException {
        Map<Integer, String> keys = AllocationTraceReader.readKeys(traceDirectory);
        Map<Integer, Tally> tallies = new TreeMap<>();
        Tally run = new Tally();
        AllocationTraceReader.forEachEvent(traceDirectory, (code, nanos) -> {
            tallies.computeIfAbsent(code, c -> new Tally()).add(nanos);
            run.add(nanos);
        });

        StringBuilder out = new StringBuilder();
        out.append("Allocation trace: ").append(traceDirectory).append('\n');
        if (run.count == 0) {
            out.append("No allocation events were recorded.\n");
            return out.toString();
        }

        long span = run.last - run.first;
        out.append(String.format("Events: %d over %.3f ms, from %d site(s)%n",
                run.count, span / 1e6, tallies.size()));
        out.append(String.format("Steady: no allocations in the last %d%% of the run.%n%n",
                (int) Math.round((1 - STEADY_BEFORE) * 100)));
        out.append(String.format("%-5s %10s %12s %12s  %-18s %s%n",
                "code", "count", "first ms", "last ms", "state", "site"));

        Bytecode bytecode = new Bytecode(classRoots);
        StringBuilder details = new StringBuilder();
        for (Map.Entry<Integer, Tally> entry : tallies.entrySet()) {
            int code = entry.getKey();
            Tally tally = entry.getValue();
            boolean steady = span == 0 || tally.last - run.first <= STEADY_BEFORE * span;
            String key = keys.get(code);
            out.append(String.format("%-5d %10d %12.3f %12.3f  %-18s %s%n", code, tally.count,
                    (tally.first - run.first) / 1e6, (tally.last - run.first) / 1e6,
                    steady ? "steady" : "STILL ALLOCATING", key == null ? "?" : shortName(key)));

            if (key == null) {
                details.append("  code ").append(code).append(": no key recorded for this code\n");
                continue;
            }
            details.append(String.format("  %d  %s%n", code, key));
            details.append("      contract: ").append(bytecode.contractOf(key)).append('\n');
            details.append("      static findings on this method: ")
                    .append(bytecode.findingsOn(key)).append('\n');
        }
        out.append('\n').append("Sites:\n").append(details);
        return out.toString();
    }

    /** {@code class#method}, without the line, offset and category. */
    private static String shortName(String key) {
        return key.substring(0, key.indexOf(':', key.indexOf('#')));
    }

    /** Count and time window for one site. Timestamps may arrive out of order across threads. */
    private static final class Tally {
        long count;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        void add(long nanos) {
            count++;
            first = Math.min(first, nanos);
            last = Math.max(last, nanos);
        }
    }

    /**
     * The bytecode side of the report: the annotations on each site's method, and the checker's
     * findings. Built lazily, so a report over a trace with no class directories does no analysis.
     */
    private static final class Bytecode {
        private final List<Path> roots;
        private final Map<String, ClassNode> classes = new HashMap<>();
        private final Map<String, Integer> findings = new HashMap<>();

        Bytecode(List<Path> roots) throws IOException {
            this.roots = roots;
            if (!roots.isEmpty()) {
                for (Finding finding : new AllocationChecker().analyze(roots, Collections.emptyList())
                        .findings()) {
                    findings.merge(finding.className() + "#" + finding.methodName(), 1, Integer::sum);
                }
            }
        }

        /** What governs the method a site key names, read from its bytecode. */
        String contractOf(String key) throws IOException {
            String[] site = parse(key);
            ClassNode owner = classNode(site[0]);
            if (owner == null) {
                return "class not found in the given directories";
            }
            MethodNode method = method(owner, site[1]);
            if (method == null) {
                return "method not found in class";
            }
            if (has(method.visibleAnnotations) || has(owner.visibleAnnotations)) {
                return "@AllocationsForWarmup";
            }
            if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) {
                return "lambda body reached from a warmup method";
            }
            return "NOT annotated - the agent should not have instrumented this";
        }

        /** How many findings the checker reports against the method a site key names. */
        int findingsOn(String key) {
            String[] site = parse(key);
            return findings.getOrDefault(site[0] + "#" + site[1], 0);
        }

        private ClassNode classNode(String binaryName) throws IOException {
            if (classes.containsKey(binaryName)) {
                return classes.get(binaryName);
            }
            ClassNode node = null;
            String internal = binaryName.replace('.', '/') + ".class";
            for (Path root : roots) {
                Path file = root.resolve(internal);
                if (Files.isRegularFile(file)) {
                    node = new ClassNode();
                    new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                    break;
                }
            }
            classes.put(binaryName, node);
            return node;
        }

        /** Prefers an annotated overload, since a site key does not carry a descriptor. */
        private static MethodNode method(ClassNode owner, String name) {
            MethodNode match = null;
            for (MethodNode method : owner.methods) {
                if (method.name.equals(name)) {
                    if (has(method.visibleAnnotations)) {
                        return method;
                    }
                    match = match == null ? method : match;
                }
            }
            return match;
        }

        private static boolean has(List<AnnotationNode> annotations) {
            return annotations != null && annotations.stream().anyMatch(a -> WARMUP.equals(a.desc));
        }

        /** Splits {@code class#method:line@offset:CATEGORY} into {@code [class, method]}. */
        private static String[] parse(String key) {
            int hash = key.indexOf('#');
            int colon = key.indexOf(':', hash);
            return new String[] {key.substring(0, hash), key.substring(hash + 1, colon)};
        }
    }
}
