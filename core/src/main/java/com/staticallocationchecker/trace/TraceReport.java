package com.staticallocationchecker.trace;

import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Contract;
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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Turns a trace written by the agent into a report, and says what each site means under the
 * annotations that govern it.
 *
 * <p>The trace starts with a <em>warm-up period</em>, which the agent records. Allocations in it are
 * expected: classes load, caches fill, buffers grow to their working size. Only allocations after the
 * warm-up period count as steady state, and what they mean depends on the contract the site is under.
 * That contract is resolved by {@link AllocationChecker#contracts}, so a site is held to exactly what the
 * static checker holds its method to - declared, type-level or inherited from a supertype:
 *
 * <ul>
 *   <li>{@code warmup} - an {@code @AllocationsForWarmup} method (or type). It is {@code steady} if it
 *       makes no allocations after warm-up, and {@code STILL ALLOCATING} if it does.</li>
 *   <li>{@code zero-allocation} - a {@code @ZeroAllocations} method (or type). Allocating after warm-up
 *       is a {@code VIOLATION}. During warm-up it is {@code warm-up only}, which is allowed.</li>
 *   <li>{@code conflicting} - both annotations on one method. The checker reports that as a mistake in
 *       the declaration, so the site is counted, not judged.</li>
 *   <li>{@code unannotated} - no contract applies. The site is reported with its counts, not judged.</li>
 *   <li>{@code unknown} - the class or method was not among the directories given, or several overloads
 *       share the name and the site's line does not say which, so its contract cannot be read.</li>
 * </ul>
 *
 * <p>Usage: {@code TraceReport <trace-directory> [classes-directory...]}. Without class directories the
 * trace is still reported, but every site is {@code unknown}.
 */
public final class TraceReport {

    /** The contract a site falls under, as the report labels it. */
    enum SiteContract {
        WARMUP("warmup"),
        ZERO_ALLOCATIONS("zero-allocation"),
        CONFLICTING("conflicting"),
        UNANNOTATED("unannotated"),
        UNKNOWN("unknown");

        final String label;

        SiteContract(String label) {
            this.label = label;
        }

        static SiteContract of(Contract contract) {
            switch (contract) {
                case WARMUP:
                    return WARMUP;
                case ZERO_ALLOCATIONS:
                    return ZERO_ALLOCATIONS;
                case CONFLICTING:
                    return CONFLICTING;
                default:
                    return UNANNOTATED;
            }
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
        AllocationTraceReader.forEachEvent(traceDirectory, (code, millis) -> {
            tallies.computeIfAbsent(code, c -> new Tally()).add(millis, boundary);
            run.add(millis, boundary);
        });

        StringBuilder out = new StringBuilder();
        out.append("Allocation trace: ").append(traceDirectory).append('\n');
        if (run.count == 0) {
            out.append("No allocation events were recorded.\n");
            return out.toString();
        }

        out.append(String.format("Events: %d over %d ms, from %d site(s)%n",
                run.count, run.last - run.first, tallies.size()));
        if (warmupEnd.isPresent() && warmupEnd.getAsLong() > run.first) {
            out.append(String.format("Warm-up: ends %d ms into the run. Only allocations after that are steady state.%n%n",
                    warmupEnd.getAsLong() - run.first));
        } else if (warmupEnd.isPresent()) {
            out.append("Warm-up had already ended before the first allocation: every event is steady state.\n\n");
        } else {
            out.append("No warm-up period was recorded: every event counts as steady state.\n\n");
        }

        Bytecode bytecode = new Bytecode(classRoots);
        Map<SiteContract, long[]> totals = new EnumMap<>(SiteContract.class);
        Map<SiteContract, Integer> sites = new EnumMap<>(SiteContract.class);
        Map<SiteContract, Integer> stillAllocating = new EnumMap<>(SiteContract.class);
        Map<SiteContract, Integer> violations = new EnumMap<>(SiteContract.class);

        StringBuilder rows = new StringBuilder();
        StringBuilder details = new StringBuilder();
        for (Map.Entry<Integer, Tally> entry : tallies.entrySet()) {
            int code = entry.getKey();
            Tally tally = entry.getValue();
            String key = keys.get(code);
            Bytecode.Resolution resolution = key == null ? null : bytecode.resolve(key);
            SiteContract contract = resolution == null ? SiteContract.UNKNOWN : resolution.contract;
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

            rows.append(String.format("%-5d %10d %10d %10d %12d %12d  %-15s %-17s %s%n",
                    code, tally.count, tally.warmup, tally.steady,
                    tally.first - run.first, tally.last - run.first,
                    contract.label, state, key == null ? "?" : shortName(key)));

            if (key == null) {
                details.append("  ").append(code).append(": no key recorded for this code\n");
                continue;
            }
            details.append(String.format("  %d  %s%n", code, key));
            details.append("      contract: ").append(resolution.description).append('\n');
            details.append("      static findings on this method: ")
                    .append(bytecode.findingsOn(key)).append('\n');
        }

        out.append("By contract:\n");
        for (SiteContract contract : SiteContract.values()) {
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
    static String stateOf(SiteContract contract, Tally tally) {
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

        void add(long millis, long boundary) {
            count++;
            if (millis < boundary) {
                warmup++;
            } else {
                steady++;
            }
            first = Math.min(first, millis);
            last = Math.max(last, millis);
        }
    }

    /**
     * The bytecode side of the report: the contract each site's method is held to, and the checker's
     * findings. Both come from {@link AllocationChecker}, so the report and the static check cannot
     * disagree about what a method was promised.
     */
    static final class Bytecode {

        /** A site's contract, and a sentence saying why, for the details section. */
        static final class Resolution {
            final SiteContract contract;
            final String description;

            Resolution(SiteContract contract, String description) {
                this.contract = contract;
                this.description = description;
            }
        }

        private final List<Path> roots;
        private final Map<String, ClassNode> classes = new HashMap<>();
        private final Map<String, Resolution> resolved = new HashMap<>();
        private final Map<String, Contract> contracts = new HashMap<>();
        private final Map<String, Integer> findings = new HashMap<>();

        Bytecode(List<Path> roots) {
            this.roots = roots;
            if (!roots.isEmpty()) {
                AllocationChecker checker = new AllocationChecker();
                contracts.putAll(checker.contracts(roots, Collections.emptyList()));
                for (Finding finding : checker.analyze(roots, Collections.emptyList()).findings()) {
                    findings.merge(finding.className() + "#" + finding.methodName(), 1, Integer::sum);
                }
            }
        }

        /** The contract of the method a site key names. */
        Resolution resolve(String key) {
            return resolved.computeIfAbsent(key, this::resolveUncached);
        }

        private Resolution resolveUncached(String key) {
            Site site = Site.parse(key);
            ClassNode owner = classNode(site.className);
            if (owner == null) {
                return new Resolution(SiteContract.UNKNOWN, "class not found in the given directories");
            }
            List<MethodNode> named = new ArrayList<>();
            for (MethodNode method : owner.methods) {
                if (method.name.equals(site.methodName)) {
                    named.add(method);
                }
            }
            if (named.isEmpty()) {
                return new Resolution(SiteContract.UNKNOWN, "method not found in class");
            }
            MethodNode method = named.size() == 1 ? named.get(0) : overloadContaining(named, site.line);
            if (method == null) {
                return new Resolution(SiteContract.UNKNOWN, "ambiguous: " + named.size()
                        + " overloads of " + site.methodName + ", and line " + site.line + " does not pick one");
            }
            Contract contract = contracts.get(site.className + "#" + method.name + method.desc);
            if (contract == null) {
                return new Resolution(SiteContract.UNKNOWN, "method not analysed");
            }
            return new Resolution(SiteContract.of(contract), describe(contract, method));
        }

        /** The one overload whose line table contains {@code line}, or null if not exactly one. */
        private static MethodNode overloadContaining(List<MethodNode> overloads, int line) {
            if (line < 0) {
                return null;
            }
            MethodNode match = null;
            for (MethodNode method : overloads) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LineNumberNode && ((LineNumberNode) insn).line == line) {
                        if (match != null && match != method) {
                            return null;
                        }
                        match = method;
                        break;
                    }
                }
            }
            return match;
        }

        private static String describe(Contract contract, MethodNode method) {
            switch (contract) {
                case WARMUP:
                    return "@AllocationsForWarmup";
                case ZERO_ALLOCATIONS:
                    return "@ZeroAllocations";
                case CONFLICTING:
                    return "conflicting: both @ZeroAllocations and @AllocationsForWarmup on one method";
                default:
                    return (method.access & Opcodes.ACC_SYNTHETIC) != 0
                            ? "unannotated (synthetic lambda body)"
                            : "unannotated";
            }
        }

        /** How many findings the checker reports against the method a site key names. */
        int findingsOn(String key) {
            Site site = Site.parse(key);
            return findings.getOrDefault(site.className + "#" + site.methodName, 0);
        }

        /** Reads a class from the roots, with its code, since overloads are told apart by line. */
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
                        new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES);
                    } catch (IOException e) {
                        node = null;
                    }
                    break;
                }
            }
            classes.put(binaryName, node);
            return node;
        }
    }

    /** A site key, {@code class#method:line@offset:CATEGORY}, taken apart. */
    static final class Site {
        final String className;
        final String methodName;
        final int line;

        private Site(String className, String methodName, int line) {
            this.className = className;
            this.methodName = methodName;
            this.line = line;
        }

        static Site parse(String key) {
            int hash = key.indexOf('#');
            int colon = key.indexOf(':', hash);
            int at = key.indexOf('@', colon);
            return new Site(key.substring(0, hash), key.substring(hash + 1, colon),
                    Integer.parseInt(key.substring(colon + 1, at)));
        }
    }
}
