package com.staticallocationchecker.instrument;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A {@link ClassFileTransformer} that rewrites warmup methods via {@link WarmupInstrumenter}.
 *
 * <p>Per the transformer contract it returns null when the class is unchanged, and it never throws
 * (a thrown transformer would abort class loading), degrading to "no transformation" on any error.
 *
 * <p>It does, however, say so. A transformer that fails silently is indistinguishable from one with
 * nothing to do, which is how a misassembled agent can appear to start cleanly while instrumenting
 * nothing at all. Each distinct failure is reported once, so a systemic problem shows up on the
 * first class it affects without producing a per-class flood.
 */
final class WarmupClassFileTransformer implements ClassFileTransformer {

    private final Consumer<String> diagnostics;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    WarmupClassFileTransformer() {
        this(message -> System.err.println("static-allocation-checker: " + message));
    }

    WarmupClassFileTransformer(Consumer<String> diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        try {
            ClassLoader resolver = loader != null ? loader : ClassLoader.getSystemClassLoader();
            return new WarmupInstrumenter(resolver).instrument(classfileBuffer);
        } catch (Throwable t) {
            report(className, t);
            return null;
        }
    }

    /** Reports the first occurrence of each distinct failure, naming a class it was seen on. */
    private void report(String className, Throwable failure) {
        String signature = failure.getClass().getName() + ": " + failure.getMessage();
        if (!reported.add(signature)) {
            return;
        }
        try {
            diagnostics.accept("failed to instrument " + className + " (" + signature
                    + "); classes hitting this are NOT being monitored."
                    + " Further identical failures will not be reported.");
        } catch (Throwable ignored) {
            // Reporting must never itself become the reason a class fails to load.
        }
    }
}
