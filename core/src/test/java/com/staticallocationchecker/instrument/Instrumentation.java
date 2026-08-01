package com.staticallocationchecker.instrument;

/** Test helpers for instrumenting, loading and invoking fixture classes in-memory. */
final class Instrumentation {

    private Instrumentation() {
    }

    /** The raw bytecode of an already-compiled class, read from the test classpath. */
    static byte[] originalBytes(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No bytecode for " + type);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read bytecode for " + type, e);
        }
    }

    /**
     * Loads {@code type} from the given (instrumented) bytes in a fresh classloader that defines
     * only that class and delegates everything else (recorder, annotations) to the parent, so the
     * instrumented code and the test observe the same recorder.
     */
    static Class<?> defineInstrumented(Class<?> type, byte[] bytes) throws ClassNotFoundException {
        return new SingleClassLoader(type.getClassLoader(), type.getName(), bytes).loadClass(type.getName());
    }

    private static final class SingleClassLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        SingleClassLoader(ClassLoader parent, String name, byte[] bytes) {
            super(parent);
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
            if (!requested.equals(name)) {
                return super.loadClass(requested, resolve);
            }
            Class<?> loaded = findLoadedClass(requested);
            if (loaded == null) {
                loaded = defineClass(requested, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
