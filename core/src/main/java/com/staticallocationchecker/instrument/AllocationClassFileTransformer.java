package com.staticallocationchecker.instrument;

import com.staticallocationchecker.runtime.AllocationTrace;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A {@link ClassFileTransformer} that records every allocation site in a class via
 * {@link AllocationInstrumenter}.
 *
 * <p>Per the transformer contract it returns null when the class is unchanged, and it never throws
 * (a thrown transformer would abort class loading), degrading to "no transformation" on any error.
 *
 * <p>Three kinds of class are left alone, because instrumenting them would break the recorder
 * rather than measure the program:
 *
 * <ul>
 *   <li>bootstrap classes (null loader), which cannot see the trace the probe calls;</li>
 *   <li>classes from loaders that cannot see the trace either, which would fail at their first probe;</li>
 *   <li>the agent's own classes, which would otherwise record their own work and could recurse.</li>
 * </ul>
 *
 * <p>It does say so when it declines a loader. A transformer that fails silently is indistinguishable
 * from one with nothing to do, which is how a misassembled agent can appear to start cleanly while
 * instrumenting nothing at all. Each distinct failure is reported once, so a systemic problem shows
 * up on the first class it affects without producing a per-class flood.
 */
final class AllocationClassFileTransformer implements ClassFileTransformer {

    /**
     * The agent's own packages. Instrumenting them would record the recorder recording itself. Only
     * these are excluded, not the whole namespace: an application may well live under
     * {@code com.staticallocationchecker} too.
     */
    private static final String[] AGENT_PACKAGES = {
        "com/staticallocationchecker/runtime/",
        "com/staticallocationchecker/instrument/",
        "com/staticallocationchecker/trace/",
        "com/staticallocationchecker/shaded/",
    };

    private final AllocationTrace trace;
    private final Consumer<String> diagnostics;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final Map<ClassLoader, Boolean> recorderVisible =
            Collections.synchronizedMap(new WeakHashMap<>());

    AllocationClassFileTransformer(AllocationTrace trace) {
        this(trace, message -> System.err.println("static-allocation-checker: " + message));
    }

    AllocationClassFileTransformer(AllocationTrace trace, Consumer<String> diagnostics) {
        this.trace = trace;
        this.diagnostics = diagnostics;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (loader == null || className == null || isAgentClass(className) || !recorderVisible(loader)) {
            return null;
        }
        try {
            return new AllocationInstrumenter(loader, trace::siteCode).instrument(classfileBuffer);
        } catch (Throwable t) {
            report(className, t);
            return null;
        }
    }

    /** Whether an internal class name (slash-separated) belongs to the agent itself. */
    static boolean isAgentClass(String internalName) {
        for (String agentPackage : AGENT_PACKAGES) {
            if (internalName.startsWith(agentPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code loader} resolves the trace to the same class the agent installed. Checked once
     * per loader: a loader that cannot see it would otherwise fail at the first probe it defines.
     */
    private boolean recorderVisible(ClassLoader loader) {
        Boolean known = recorderVisible.get(loader);
        if (known == null) {
            known = sees(loader);
            recorderVisible.put(loader, known);
            if (!known) {
                reportOnce("cannot-see-trace:" + loader.getClass().getName(),
                        "class loader " + loader.getClass().getName() + " cannot see the allocation trace; "
                                + "classes it defines are NOT being recorded.");
            }
        }
        return known;
    }

    private static boolean sees(ClassLoader loader) {
        try {
            return Class.forName(AllocationTrace.class.getName(), false, loader) == AllocationTrace.class;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Reports the first occurrence of each distinct failure, naming a class it was seen on. */
    private void report(String className, Throwable failure) {
        String signature = failure.getClass().getName() + ": " + failure.getMessage();
        reportOnce(signature, "failed to instrument " + className + " (" + signature
                + "); classes hitting this are NOT being monitored."
                + " Further identical failures will not be reported.");
    }

    private void reportOnce(String signature, String message) {
        if (!reported.add(signature)) {
            return;
        }
        try {
            diagnostics.accept(message);
        } catch (Throwable ignored) {
            // Reporting must never itself become the reason a class fails to load.
        }
    }
}
