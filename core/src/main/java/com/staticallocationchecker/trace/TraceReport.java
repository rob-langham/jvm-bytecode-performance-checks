package com.staticallocationchecker.trace;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.runtime.AllocationTraceReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Turns a trace written by the agent into a report, and says what each site means under the
 * annotations that govern it.
 *
 * <p>The trace starts with a <em>warm-up period</em>, which the agent records. Allocations in it are
 * expected: classes load, caches fill, buffers grow to their working size. Only allocations after the
 * warm-up period count as steady state, and what they mean depends on the contract the site is under,
 * read from the bytecode:
 *
 * <ul>
 *   <li>{@code warmup} - an {@code @AllocationsForWarmup} method (or type). It is {@code steady} if it
 *       makes no allocations after warm-up, and {@code STILL ALLOCATING} if it does.</li>
 *   <li>{@code zero-allocation} - a {@code @ZeroAllocations} method (or type). Allocating after warm-up
 *       is a {@code VIOLATION}. During warm-up it is {@code warm-up only}, which is allowed.</li>
 *   <li>{@code unannotated} - no contract applies. The site is reported with its counts, not judged.</li>
 *   <li>{@code unknown} - the class was not among the directories given, so its annotations cannot be read.</li>
 * </ul>
 *
 * <p>Usage: {@code TraceReport <trace-directory> [classes-directory...]}. Without class directories the
 * trace is still reported, but every site is {@code unknown}.
 */
public final class TraceReport {

    private static final String WARMUP = "Lcom/staticallocationchecker/annotations/AllocationsForWarmup;";
    private static final String ZERO = "Lcom/staticallocationchecker/annotations/ZeroAllocations;";

    /** The contract a site falls under, from its bytecode. */
    enum Contract {
        WARMUP("warmup"),
        ZERO_ALLOCATIONS("zero-allocation"),
        UNANNOTATED("unannotated"),
        UNKNOWN("unknown");

        final String label;

        Contract(String label) {
            this.label = label;
        }
    }

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
        OptionalLong warmupEnd = AllocationTraceReader.warmupEnd(traceDirectory);
        // Without a recorded warm-up nothing is warm-up: every event is steady state.
        long boundary = warmupEnd.orElse(Long.MIN_VALUE);

        Map<Integer, Tally> tallies = new TreeMap<>();
        Tally run = new Tally();
        AllocationTraceReader.forEachEvent(traceDirectory, (code, nanos) -> {
            tallies.computeIfAbsent(code, c -> new Tally()).add(nanos, boundary);
            run.add(nanos, boundary);
        });

        StringBuilder out = new StringBuilder();
        out.append("Allocation trace: ").append(traceDirectory).append('\n');
        if (run.count == 0) {
            out.append("No allocation events were recorded.\n");
            return out.toString();
        }

        out.append(String.format("Events: %d over %.3f ms, from %d site(s)%n",
                run.count, (run.last - run.first) / 1e6, tallies.size()));
        if (warmupEnd.isPresent() && warmupEnd.getAsLong() > run.first) {
            out.append(String.format("Warm-up: ends %.3f ms into the run. Only allocations after that are steady state.%n%n",
                    (warmupEnd.getAsLong() - run.first) / 1e6));
        } else if (warmupEnd.isPresent()) {
            out.append("Warm-up had already ended before the first allocation: every event is steady state.\n\n");
        } else {
            out.append("No warm-up period was recorded (warmup=0): every event counts as steady state.\n\n");
        }

        Bytecode bytecode = new Bytecode(classRoots);
        Map<Contract, long[]> totals = new EnumMap<>(Contract.class);
        Map<Contract, Integer> sites = new EnumMap<>(Contract.class);
        Map<Contract, Integer> stillAllocating = new EnumMap<>(Contract.class);
        Map<Contract, Integer> violations = new EnumMap<>(Contract.class);

        StringBuilder rows = new StringBuilder();
        StringBuilder details = new StringBuilder();
        for (Map.Entry<Integer, Tally> entry : tallies.entrySet()) {
            int code = entry.getKey();
            Tally tally = entry.getValue();
            String key = keys.get(code);
            Contract contract = key == null ? Contract.UNKNOWN : bytecode.contractOf(key);
            String state = stateOf(contract, tally);

            long[] sum = totals.computeIfAbsent(contract, c -> new long[2]);
            sum[0] += tally.warmup;
            sum[1] += tally.steady;
            sites.merge(contract, 1, Integer::sum);
            if (state.equals("STILL ALLOCATING")) {
                stillAllocating.merge(contract, 1, Integer::sum);
            } else if (state.equals("VIOLATION")) {
                violations.merge(contract, 1, Integer::sum);
            }

            rows.append(String.format("%-5d %10d %10d %10d %12.3f %12.3f  %-15s %-17s %s%n",
                    code, tally.count, tally.warmup, tally.steady,
                    (tally.first - run.first) / 1e6, (tally.last - run.first) / 1e6,
                    contract.label, state, key == null ? "?" : shortName(key)));

            if (key == null) {
                details.append("  ").append(code).append(": no key recorded for this code\n");
                continue;
            }
            details.append(String.format("  %d  %s%n", code, key));
            details.append("      contract: ").append(bytecode.describe(key)).append('\n');
            details.append("      static findings on this method: ")
                    .append(bytecode.findingsOn(key)).append('\n');
        }

        out.append("By contract:\n");
        for (Contract contract : Contract.values()) {
            if (!sites.containsKey(contract)) {
                continue;
            }
            long[] sum = totals.get(contract);
            out.append(String.format(
                    "  %-15s sites %4d  warm-up events %10d  steady events %10d  still allocating %d  violations %d%n",
                    contract.label, sites.get(contract), sum[0], sum[1],
                    stillAllocating.getOrDefault(contract, 0), violations.getOrDefault(contract, 0)));
        }

        out.append('\n');
        out.append(String.format("%-5s %10s %10s %10s %12s %12s  %-15s %-17s %s%n",
                "code", "count", "warm-up", "steady", "first ms", "last ms", "contract", "state", "site"));
        out.append(rows);
        out.append('\n').append("Sites:\n").append(details);
        return out.toString();
    }

    /** What a site's events say, given the contract it is under. */
    static String stateOf(Contract contract, Tally tally) {
        switch (contract) {
            case WARMUP:
                return tally.steady == 0 ? "steady" : "STILL ALLOCATING";
            case ZERO_ALLOCATIONS:
                return tally.steady == 0 ? "warm-up only" : "VIOLATION";
            default:
                return "-";
        }
    }

    /** {@code class#method}, without the line, offset and category. */
    private static String shortName(String key) {
        return key.substring(0, key.indexOf(':', key.indexOf('#')));
    }

    /**
     * Counts for one site, split at the warm-up boundary. Timestamps may arrive out of order across
     * threads, so first and last are min and max rather than the first and last seen.
     */
    static final class Tally {
        long count;
        long warmup;
        long steady;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        void add(long nanos, long boundary) {
            count++;
            if (nanos < boundary) {
                warmup++;
            } else {
                steady++;
            }
            first = Math.min(first, nanos);
            last = Math.max(last, nanos);
        }
    }

    /**
     * The bytecode side of the report: the annotations on each site's method and class, and the
     * checker's findings. Classes are read lazily, so a report over a trace with no class directories
     * does no analysis.
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

        /** The contract that governs the method a site key names. */
        Contract contractOf(String key) {
            String[] site = parse(key);
            ClassNode owner = classNode(site[0]);
            if (owner == null) {
                return Contract.UNKNOWN;
            }
            MethodNode method = method(owner, site[1]);
            if (method == null) {
                return Contract.UNKNOWN;
            }
            // A method-level annotation is more specific than a type-level one, so it wins.
            Contract own = contractOf(method.visibleAnnotations);
            if (own != Contract.UNANNOTATED) {
                return own;
            }
            return contractOf(owner.visibleAnnotations);
        }

        /** A sentence saying what the contract is and why, for the details section. */
        String describe(String key) {
            String[] site = parse(key);
            ClassNode owner = classNode(site[0]);
            if (owner == null) {
                return "class not found in the given directories";
            }
            MethodNode method = method(owner, site[1]);
            if (method == null) {
                return "method not found in class";
            }
            switch (contractOf(key)) {
                case WARMUP:
                    return "@AllocationsForWarmup";
                case ZERO_ALLOCATIONS:
                    return "@ZeroAllocations";
                default:
                    return (method.access & Opcodes.ACC_SYNTHETIC) != 0
                            ? "unannotated (synthetic lambda body)"
                            : "unannotated";
            }
        }

        /** How many findings the checker reports against the method a site key names. */
        int findingsOn(String key) {
            String[] site = parse(key);
            return findings.getOrDefault(site[0] + "#" + site[1], 0);
        }

        private static Contract contractOf(List<AnnotationNode> annotations) {
            if (annotations == null) {
                return Contract.UNANNOTATED;
            }
            for (AnnotationNode annotation : annotations) {
                if (WARMUP.equals(annotation.desc)) {
                    return Contract.WARMUP;
                }
                if (ZERO.equals(annotation.desc)) {
                    return Contract.ZERO_ALLOCATIONS;
                }
            }
            return Contract.UNANNOTATED;
        }

        private ClassNode classNode(String binaryName) {
            if (classes.containsKey(binaryName)) {
                return classes.get(binaryName);
            }
            ClassNode node = null;
            String internal = binaryName.replace('.', '/') + ".class";
            for (Path root : roots) {
                Path file = root.resolve(internal);
                if (Files.isRegularFile(file)) {
                    node = new ClassNode();
                    try {
                        new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                    } catch (IOException e) {
                        node = null;
                    }
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
                    if (contractOf(method.visibleAnnotations) != Contract.UNANNOTATED) {
                        return method;
                    }
                    match = match == null ? method : match;
                }
            }
            return match;
        }

        /** Splits {@code class#method:line@offset:CATEGORY} into {@code [class, method]}. */
        private static String[] parse(String key) {
            int hash = key.indexOf('#');
            int colon = key.indexOf(':', hash);
            return new String[] {key.substring(0, hash), key.substring(hash + 1, colon)};
        }
    }
}
