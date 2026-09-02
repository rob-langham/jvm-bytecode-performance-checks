package com.staticallocationchecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The complete set of findings from an analysis run. */
public final class Report {

    private final List<Finding> findings;

    public Report(List<Finding> findings) {
        this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
    }

    public List<Finding> findings() {
        return findings;
    }

    /** Whether no findings were produced. */
    public boolean isClean() {
        return findings.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Report && findings.equals(((Report) o).findings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(findings);
    }

    @Override
    public String toString() {
        return "Report" + findings;
    }
}
