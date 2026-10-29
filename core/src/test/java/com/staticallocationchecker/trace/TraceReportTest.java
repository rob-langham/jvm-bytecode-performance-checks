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

    private static final String WARM = "com.staticallocationchecker.instrument.fixtures.WarmupTarget#warm:24@12:NEW";
    private static final String MISSING_METHOD = "com.staticallocationchecker.instrument.fixtures.WarmupTarget#gone:1@0:NEW";
    private static final String MISSING_CLASS = "no.such.Clazz#run:1@0:NEW";

    @TempDir
    Path dir;

    /** The directory the fixture classes were compiled into, so the report can read their bytecode. */
    private static Path fixtureClasses() throws Exception {
        return Paths.get(WarmupTarget.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * A site that fired early and then stopped, and a site that was still allocating at the end.
     * The span is 100..2000 ns; the steady cut-off is 100 + 0.8 * 1900 = 1620 ns.
     */
    private void writeTrace() throws Exception {
        AtomicLong now = new AtomicLong();
        AllocationTrace trace = AllocationTrace.open(dir, now::get);
        int warm = trace.siteCode(WARM);
        int missingMethod = trace.siteCode(MISSING_METHOD);
        int missingClass = trace.siteCode(MISSING_CLASS);

        now.set(100);
        trace.record(warm);
        trace.record(missingMethod);
        now.set(200);
        trace.record(warm);
        now.set(2000);
        trace.record(missingClass);
        trace.close();
    }

    @Test
    void reportsCountsTimesAndSteadyStateForEachSite() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("Events: 4 over 0.002 ms, from 3 site(s)"), report);
        assertTrue(report.matches("(?s).*\\n0\\s+2\\s+0\\.000\\s+0\\.000\\s+steady\\s+\\S+#warm\\n.*"),
                () -> "the early site fired twice and then stopped:\n" + report);
        assertTrue(report.contains("STILL ALLOCATING"), () -> "the late site should be flagged:\n" + report);
    }

    @Test
    void namesTheContractGoverningEachSiteFromItsBytecode() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("contract: @AllocationsForWarmup"),
                () -> "WarmupTarget#warm is annotated:\n" + report);
        assertTrue(report.contains("method not found in class"), report);
        assertTrue(report.contains("class not found in the given directories"), report);
    }

    @Test
    void withoutClassDirectoriesItStillReportsTheTrace() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of());

        assertTrue(report.contains("STILL ALLOCATING"), report);
        assertTrue(report.contains("class not found in the given directories"), report);
    }

    @Test
    void anEmptyTraceSaysSo() throws Exception {
        AllocationTrace.open(dir, () -> 0L).close();

        assertTrue(TraceReport.render(dir, List.of()).contains("No allocation events were recorded."));
    }
}
