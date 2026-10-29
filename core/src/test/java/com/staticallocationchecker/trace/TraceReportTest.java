package com.staticallocationchecker.trace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import com.staticallocationchecker.runtime.AllocationTrace;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceReportTest {

    private static final String PKG = "com.staticallocationchecker.instrument.fixtures.";

    @TempDir
    Path dir;

    /** The directory the fixture classes were compiled into, so the report can read their bytecode. */
    private static Path fixtureClasses() throws Exception {
        return Paths.get(WarmupTarget.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * Six sites, one per situation the report must tell apart. The run spans 100..2000 ns, so the
     * steady cut-off is 100 + 0.8 * 1900 = 1620 ns.
     */
    private void writeTrace() throws Exception {
        AtomicLong now = new AtomicLong();
        AllocationTrace trace = AllocationTrace.open(dir, now::get);
        int settled = trace.siteCode(PKG + "WarmupTarget#warm:24@12:NEW");   // warmup, stops at 200
        int still = trace.siteCode(PKG + "WarmupTarget#warm:24@30:NEW");    // warmup, runs to the end
        int missingMethod = trace.siteCode(PKG + "WarmupTarget#gone:1@0:NEW");
        int missingClass = trace.siteCode("no.such.Clazz#run:1@0:NEW");
        int zero = trace.siteCode("com.staticallocationchecker.fixtures.TypeLevelZeroAllocations#first:1@0:NEW");
        int plain = trace.siteCode(PKG + "NotWarmup#make:1@0:NEW");

        now.set(100);
        trace.record(settled);
        trace.record(still);
        trace.record(missingMethod);
        now.set(150);
        trace.record(zero);
        now.set(160);
        trace.record(plain);
        now.set(200);
        trace.record(settled);
        now.set(2000);
        trace.record(still);
        trace.record(missingClass);
        trace.close();
    }

    @Test
    void reportsCountsTimesAndStateOfAWarmupSite() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("Events: 8 over 0.002 ms, from 6 site(s)"), report);
        assertTrue(report.matches("(?s).*\\n0\\s+2\\s+0\\.000\\s+0\\.000\\s+warmup\\s+steady\\s+\\S+#warm\\n.*"),
                () -> "the settled site fired twice, then stopped:\n" + report);
        assertTrue(report.matches("(?s).*\\n1\\s+2\\s+0\\.000\\s+0\\.002\\s+warmup\\s+STILL ALLOCATING\\s+\\S+#warm\\n.*"),
                () -> "the other warmup site was still allocating at the end:\n" + report);
    }

    @Test
    void aSiteUnderZeroAllocationsIsAViolation() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.matches("(?s).*\\n4\\s+1\\s+0\\.000\\s+0\\.000\\s+zero-allocation\\s+VIOLATION\\s+\\S+#first\\n.*"),
                () -> "an allocation under @ZeroAllocations must be flagged:\n" + report);
        assertTrue(report.matches("(?s).*zero-allocation\\s+sites\\s+1\\s+events\\s+1\\s+still allocating 0\\s+violations 1.*"),
                () -> "the summary should count the violation:\n" + report);
    }

    @Test
    void anAllocationWithNoContractIsReportedButNotJudged() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.matches("(?s).*\\n5\\s+1\\s+0\\.000\\s+0\\.000\\s+unannotated\\s+-\\s+\\S+#make\\n.*"),
                () -> "unannotated sites are counted, not judged:\n" + report);
    }

    @Test
    void aSiteWhoseMethodOrClassCannotBeReadIsUnknown() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("method not found in class"), report);
        assertTrue(report.contains("class not found in the given directories"), report);
        assertTrue(report.matches("(?s).*\\n3\\s+1\\s+.*unknown\\s+-\\s+no\\.such\\.Clazz#run\\n.*"), report);
    }

    @Test
    void withoutClassDirectoriesEverySiteIsUnknownButTheTraceIsStillReported() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of());

        assertTrue(report.contains("Events: 8 over"), report);
        assertTrue(!report.contains("warmup  ") && !report.contains("zero-allocation  "),
                () -> "no contract can be read without the classes:\n" + report);
    }

    @Test
    void anEmptyTraceSaysSo() throws Exception {
        AllocationTrace.open(dir, () -> 0L).close();

        assertTrue(TraceReport.render(dir, List.of()).contains("No allocation events were recorded."));
    }
}
