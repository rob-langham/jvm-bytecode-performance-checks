package com.staticallocationchecker;

import java.nio.file.Path;
import java.util.List;

/** Test helpers for locating compiled fixtures and querying findings. */
final class Fixtures {

    private Fixtures() {
    }

    /** The directory holding the compiled test classes (build/classes/java/test). */
    static Path testClassesRoot() {
        try {
            return Path.of(Fixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate test classes root", e);
        }
    }

    /** Findings whose site is in the given fixture class. */
    static List<Finding> findingsFor(Report report, Class<?> fixture) {
        return report.findings().stream()
                .filter(f -> f.className().equals(fixture.getName()))
                .toList();
    }
}
