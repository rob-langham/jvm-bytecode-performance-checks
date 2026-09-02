import com.staticallocationchecker.AllocationCategory;
import com.staticallocationchecker.AllocationChecker;
import com.staticallocationchecker.Finding;
import com.staticallocationchecker.Report;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Runs the checker over the probe bytecode, on the same JDK that compiled it - this source stays
 * Java-8 compatible (no List.of, no Path.of) precisely so the JDK 8 leg can execute it. It fails
 * unless the three planted allocations are all found with the right categories. Categories, not a
 * count: the whole point of the old-JDK legs is that a release-8 concatenation must still read
 * STRING_CONCAT.
 */
public final class RunChecker {

    public static void main(String[] args) {
        Report report = new AllocationChecker().analyze(
                Collections.singletonList((Path) Paths.get(args[0])),
                Collections.<Path>emptyList());
        for (Finding finding : report.findings()) {
            System.out.println(finding);
        }

        Set<AllocationCategory> found = new HashSet<AllocationCategory>();
        for (Finding finding : report.findings()) {
            if (finding.kind() == Finding.Kind.ZERO_ALLOCATION_VIOLATION) {
                found.add(finding.category());
            }
        }
        Set<AllocationCategory> expected = new HashSet<AllocationCategory>(Arrays.asList(
                AllocationCategory.NEW, AllocationCategory.BOXING, AllocationCategory.STRING_CONCAT));

        if (!found.containsAll(expected)) {
            System.err.println("expected categories " + expected + " but found " + found);
            System.exit(1);
        }
        System.out.println("OK: " + found + " found in " + args[0]
                + " on Java " + System.getProperty("java.version"));
    }
}
