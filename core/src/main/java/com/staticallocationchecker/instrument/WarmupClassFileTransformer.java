package com.staticallocationchecker.instrument;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * A {@link ClassFileTransformer} that rewrites warmup methods via {@link WarmupInstrumenter}.
 *
 * <p>Per the transformer contract it returns null when the class is unchanged, and it never throws
 * (a thrown transformer would abort class loading), degrading to "no transformation" on any error.
 */
final class WarmupClassFileTransformer implements ClassFileTransformer {

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
            return null;
        }
    }
}
