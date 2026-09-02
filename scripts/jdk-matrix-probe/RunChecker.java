import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.Report;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the checker over the probe bytecode a matrix container compiled, and fails unless the
 * three planted allocations are all found with the right categories. Categories, not a count: the
 * whole point of the old-JDK legs is that a release-8 concatenation must still read STRING_CONCAT.
 */
public final class RunChecker {

    public static void main(String[] args) {
        Report report = new AllocationChecker().analyze(List.of(Path.of(args[0])), List.of());
        report.findings().forEach(System.out::println);

        Set<AllocationCategory> found = report.findings().stream()
                .filter(f -> f.kind() == Finding.Kind.ZERO_ALLOCATION_VIOLATION)
                .map(Finding::category)
                .collect(Collectors.toSet());
        Set<AllocationCategory> expected = Set.of(
                AllocationCategory.NEW, AllocationCategory.BOXING, AllocationCategory.STRING_CONCAT);

        if (!found.containsAll(expected)) {
            System.err.println("expected categories " + expected + " but found " + found);
            System.exit(1);
        }
        System.out.println("OK: " + found + " found in " + args[0]);
    }
}
