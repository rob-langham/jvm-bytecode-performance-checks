package com.staticallocationchecker.trace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.staticallocationchecker.instrument.AllocationInstrumenter;
import com.staticallocationchecker.instrument.fixtures.Overloads;
import com.staticallocationchecker.instrument.fixtures.WarmupTarget;
import com.staticallocationchecker.runtime.AllocationTrace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
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
     * Seven sites, one per situation the report must tell apart. The warm-up period ends at 150 ns, so
     * events at 100 ns are warm-up and events at 160 ns and later are steady state.
     */
    private void writeTrace() throws Exception {
        AtomicLong now = new AtomicLong();
        AllocationTrace trace = AllocationTrace.open(dir, 150L, now::get);
        int settled = trace.siteCode(PKG + "WarmupTarget#warm:24@12:NEW");
        int still = trace.siteCode(PKG + "WarmupTarget#warm:24@30:NEW");
        int missingMethod = trace.siteCode(PKG + "WarmupTarget#gone:1@0:NEW");
        int missingClass = trace.siteCode("no.such.Clazz#run:1@0:NEW");
        int zeroSteady = trace.siteCode("com.staticallocationchecker.fixtures.TypeLevelZeroAllocations#first:1@0:NEW");
        int zeroWarm = trace.siteCode("com.staticallocationchecker.fixtures.TypeLevelZeroAllocations#second:1@0:NEW");
        int plain = trace.siteCode(PKG + "NotWarmup#make:1@0:NEW");

        now.set(100);
        trace.record(settled);
        trace.record(still);
        trace.record(missingMethod);
        trace.record(zeroWarm);
        now.set(160);
        trace.record(zeroSteady);
        trace.record(plain);
        now.set(2000);
        trace.record(still);
        trace.record(missingClass);
        trace.close();
    }

    /** The table row for {@code code}, matched on its columns. */
    private static boolean row(String report, int code, int count, int warmup, int steady,
                               String contract, String state) {
        String pattern = "(?m)^" + code + "\\s+" + count + "\\s+" + warmup + "\\s+" + steady
                + "\\s+\\S+\\s+\\S+\\s+" + Pattern.quote(contract) + "\\s+" + Pattern.quote(state) + "\\s+\\S+$";
        return Pattern.compile(pattern).matcher(report).find();
    }

    @Test
    void theHeaderSaysWhereTheWarmUpEnds() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("Events: 8 over 1900 ms, from 7 site(s)"), report);
        assertTrue(report.contains("Warm-up: ends 50 ms into the run."), report);
    }

    @Test
    void aWarmupSiteThatStopsDuringWarmUpIsSteady() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 0, 1, 1, 0, "warmup", "steady"), () -> "settled site:\n" + report);
    }

    @Test
    void aWarmupSiteThatAllocatesAfterWarmUpIsStillAllocating() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 1, 2, 1, 1, "warmup", "STILL ALLOCATING"), () -> "still-allocating site:\n" + report);
    }

    @Test
    void aZeroAllocationSiteAfterWarmUpIsAViolation() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 4, 1, 0, 1, "zero-allocation", "VIOLATION"), () -> "violation:\n" + report);
        assertTrue(report.matches("(?s).*zero-allocation\\s+sites\\s+2\\s+warm-up events\\s+1\\s+steady events\\s+1"
                + "\\s+still allocating 0\\s+violations 1.*"), () -> "summary should count it:\n" + report);
    }

    @Test
    void aZeroAllocationSiteDuringWarmUpIsAllowed() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 5, 1, 1, 0, "zero-allocation", "warm-up only"), () -> "allowed:\n" + report);
    }

    @Test
    void anAllocationWithNoContractIsCountedButNotJudged() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 6, 1, 0, 1, "unannotated", "-"), () -> "unannotated:\n" + report);
    }

    @Test
    void aSiteWhoseMethodOrClassCannotBeReadIsUnknown() throws Exception {
        writeTrace();
        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(row(report, 2, 1, 1, 0, "unknown", "-"), report);
        assertTrue(row(report, 3, 1, 0, 1, "unknown", "-"), report);
        assertTrue(report.contains("method not found in class"), report);
        assertTrue(report.contains("class not found in the given directories"), report);
    }

    @Test
    void withoutAWarmUpRecordEveryEventIsSteadyState() throws Exception {
        writeTrace();
        Files.delete(dir.resolve(AllocationTrace.WARMUP_FILE));

        String report = TraceReport.render(dir, List.of(fixtureClasses()));

        assertTrue(report.contains("No warm-up period was recorded"), report);
        assertTrue(row(report, 0, 1, 0, 1, "warmup", "STILL ALLOCATING"),
                () -> "with no warm-up, the first allocation is already steady state:\n" + report);
    }

    @Test
    void withoutClassDirectoriesEverySiteIsUnknownButTheTraceIsStillReported() throws Exception {
        writeTrace();

        String report = TraceReport.render(dir, List.of());

        assertTrue(report.contains("Events: 8 over"), report);
        assertTrue(row(report, 0, 1, 1, 0, "unknown", "-"), () -> "no contract can be read:\n" + report);
    }

    /** One steady-state event per key, with no warm-up, reported against the fixture classes. */
    private String reportOn(String... keys) throws Exception {
        AllocationTrace trace = AllocationTrace.open(dir, 0L, () -> 100L);
        for (String key : keys) {
            trace.record(trace.siteCode(key));
        }
        trace.close();
        return TraceReport.render(dir, List.of(fixtureClasses()));
    }

    private static final String SEMANTICS = "com.staticallocationchecker.fixtures.AnnotationSemantics$";

    @Test
    void aSiteIsHeldToAContractItsMethodInheritsFromAnInterface() throws Exception {
        String report = reportOn(SEMANTICS + "UnannotatedImplementation#handle:1@0:NEW");

        assertTrue(row(report, 0, 1, 0, 1, "zero-allocation", "VIOLATION"),
                () -> "the interface's @ZeroAllocations binds the implementation, as it does for the checker:\n" + report);
    }

    @Test
    void aSiteUnderConflictingContractsIsCountedNotJudged() throws Exception {
        String report = reportOn(SEMANTICS + "BothOnOneMethod#entry:1@0:NEW");

        assertTrue(row(report, 0, 1, 0, 1, "conflicting", "-"), report);
        assertTrue(report.contains("conflicting: both @ZeroAllocations and @AllocationsForWarmup"), report);
    }

    @Test
    void aMethodLevelContractInsideATypeLevelOneFollowsTheChecker() throws Exception {
        String report = reportOn(
                SEMANTICS + "WarmupMethodInZeroAllocationType#warm:1@0:NEW",
                SEMANTICS + "WarmupMethodInZeroAllocationType#hot:1@0:NEW");

        assertTrue(row(report, 0, 1, 0, 1, "warmup", "STILL ALLOCATING"), report);
        assertTrue(row(report, 1, 1, 0, 1, "zero-allocation", "VIOLATION"), report);
    }

    @Test
    void overloadsAreToldApartByTheSiteLine() throws Exception {
        // Real keys, with the compiler's line numbers, from instrumenting the fixture.
        List<String> keys = new ArrayList<>();
        try (InputStream in = Overloads.class.getResourceAsStream("Overloads.class")) {
            new AllocationInstrumenter(getClass().getClassLoader(), key -> {
                keys.add(key);
                return keys.size() - 1;
            }).instrument(in.readAllBytes());
        }
        String zeroOverload = keys.stream().filter(k -> k.endsWith(":NEW_ARRAY")).findFirst().orElseThrow();
        String plainOverload = keys.stream().filter(k -> k.endsWith(":NEW")).findFirst().orElseThrow();
        String noLine = PKG + "Overloads#make:-1@0:NEW";

        String report = reportOn(zeroOverload, plainOverload, noLine);

        assertTrue(row(report, 0, 1, 0, 1, "zero-allocation", "VIOLATION"), () -> "make(int):\n" + report);
        assertTrue(row(report, 1, 1, 0, 1, "unannotated", "-"), () -> "make(String):\n" + report);
        assertTrue(row(report, 2, 1, 0, 1, "unknown", "-"), () -> "no line to choose by:\n" + report);
        assertTrue(report.contains("ambiguous: 2 overloads of make"), report);
    }

    @Test
    void anEmptyTraceSaysSo() throws Exception {
        AllocationTrace.open(dir, () -> 0L).close();

        assertTrue(TraceReport.render(dir, List.of()).contains("No allocation events were recorded."));
    }
}
