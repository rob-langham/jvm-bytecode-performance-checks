import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.Report;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints every finding the checker produces for a set of analysis roots, grouped by the class
 * containing the site.
 *
 * <p>The build plugins log {@code Finding.toString()}, which is exhaustive but hard to read in
 * bulk. This is the readable form, and it is what the scenario pages under {@code docs/scenarios/}
 * are written from - so that no example in the documentation is invented.
 *
 * <p>Run with the checker and ASM on the classpath; see docs/scenarios/regenerating.md.
 */
public final class FindingsReport {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: FindingsReport <analysis-root> [<analysis-root> ...]");
            System.exit(2);
        }
        List<Path> roots = Arrays.stream(args).map(Path::of).toList();
        Report report = new AllocationChecker().analyze(roots, List.of());

        Map<String, StringBuilder> byClass = new TreeMap<>();
        for (Finding finding : report.findings()) {
            byClass.computeIfAbsent(finding.className(), key -> new StringBuilder())
                    .append("  ").append(finding.kind())
                    .append("  ").append(finding.category())
                    .append("  ").append(finding.methodName()).append(finding.methodDescriptor())
                    .append(":").append(finding.line())
                    .append("\n      path: ").append(String.join(" -> ", finding.callPath()))
                    .append("\n");
        }
        byClass.forEach((className, body) -> System.out.println("=== " + className + "\n" + body));
        System.out.println("TOTAL " + report.findings().size());
    }

    private FindingsReport() {
    }
}
