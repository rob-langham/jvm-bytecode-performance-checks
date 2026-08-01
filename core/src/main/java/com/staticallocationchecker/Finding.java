package com.staticallocationchecker;

import java.util.List;
import java.util.Objects;

/**
 * A single result of an analysis: either a violation or an unanalyzable call.
 *
 * <p>Modelled as a plain immutable class rather than a record so that the Maven plugin's
 * descriptor generator (which parses these sources with an older Java parser) can read it.
 */
public final class Finding {

    private final Kind kind;
    private final String className;
    private final String methodName;
    private final String methodDescriptor;
    private final int line;
    private final AllocationCategory category;
    private final List<String> callPath;

    /**
     * @param kind             what was found
     * @param className        binary name of the class containing the site
     * @param methodName       name of the method containing the site
     * @param methodDescriptor JVM descriptor of that method
     * @param line             source line of the site, or -1 if unknown
     * @param category         allocation category, or null for {@link Kind#UNANALYZABLE_CALL}
     * @param callPath         method signatures from the annotated entry point down to the site
     */
    public Finding(
            Kind kind,
            String className,
            String methodName,
            String methodDescriptor,
            int line,
            AllocationCategory category,
            List<String> callPath) {
        this.kind = kind;
        this.className = className;
        this.methodName = methodName;
        this.methodDescriptor = methodDescriptor;
        this.line = line;
        this.category = category;
        this.callPath = List.copyOf(callPath);
    }

    public Kind kind() {
        return kind;
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String methodDescriptor() {
        return methodDescriptor;
    }

    public int line() {
        return line;
    }

    public AllocationCategory category() {
        return category;
    }

    public List<String> callPath() {
        return callPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Finding other)) {
            return false;
        }
        return line == other.line
                && kind == other.kind
                && Objects.equals(className, other.className)
                && Objects.equals(methodName, other.methodName)
                && Objects.equals(methodDescriptor, other.methodDescriptor)
                && category == other.category
                && callPath.equals(other.callPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, className, methodName, methodDescriptor, line, category, callPath);
    }

    @Override
    public String toString() {
        return "Finding[kind=" + kind
                + ", className=" + className
                + ", methodName=" + methodName
                + ", methodDescriptor=" + methodDescriptor
                + ", line=" + line
                + ", category=" + category
                + ", callPath=" + callPath + "]";
    }

    /** Classifies a finding. */
    public enum Kind {
        ZERO_ALLOCATION_VIOLATION,
        WARMUP_NOT_GUARDED,
        WARMUP_NOT_CACHED,
        UNANALYZABLE_CALL,
        /**
         * A method claims both contracts at once. They contradict each other - one forbids
         * allocation, the other permits it under conditions - so the declaration is a mistake
         * rather than something to resolve by picking a winner.
         */
        CONFLICTING_CONTRACTS,
    }
}
