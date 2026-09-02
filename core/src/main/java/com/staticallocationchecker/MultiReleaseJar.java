package com.staticallocationchecker;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Picks the class entries a JVM would actually load out of a multi-release jar (JEP 238).
 *
 * <p>A multi-release jar carries the same class more than once: a base copy at the root, and one
 * copy per {@code META-INF/versions/N/} directory for JVMs at release {@code N} or above. Indexing
 * all of them and keeping whichever arrived first means analysing a variant that may never run -
 * and an allocation that exists only in the modern copy is then silently missed, which is the exact
 * failure this tool exists to prevent.
 *
 * <p>Written by hand rather than delegating to the JDK's own versioned {@link JarFile} constructor
 * because that constructor, and {@code Runtime.Version} with it, is Java 9 API and this library is
 * compiled at {@code --release 8} so that it runs on the oldest JVM whose bytecode it analyses. The
 * rules are short enough to state directly.
 */
final class MultiReleaseJar {

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    /** JEP 238 numbers the versioned directories from 9; anything lower is not a versioned entry. */
    private static final int LOWEST_VERSIONED_RELEASE = 9;

    private MultiReleaseJar() {
    }

    /**
     * The {@code .class} entries this archive contributes at the given release, keyed by the
     * resource name the class is loaded under (so {@code com/example/Foo.class}, never the
     * {@code META-INF/versions/...} path it may have come from).
     *
     * <p>The rules, which are the spec's:
     *
     * <ul>
     *   <li>Versioned entries count only if the manifest says {@code Multi-Release: true}. Without
     *       that attribute the jar is an ordinary jar that happens to have files under
     *       {@code META-INF}, and every JVM ignores them - so this does too.
     *   <li>A versioned entry for release {@code N} beats the base entry when
     *       {@code 9 <= N <= targetRelease}; the highest such {@code N} wins.
     *   <li>Entries above {@code targetRelease} are invisible, exactly as they are to a JVM at that
     *       release.
     *   <li>A versioned entry with no base entry is still a class, and a JVM at a high enough
     *       release will load it. It is indexed - including for annotation discovery - whenever the
     *       target admits it.
     * </ul>
     *
     * @param targetRelease the Java release the analysed code will run on. Anything below 9,
     *                      {@code 0} included, means no versioned entry is ever admitted: base
     *                      entries only.
     */
    static Map<String, JarEntry> classEntries(JarFile jar, int targetRelease) throws IOException {
        boolean multiRelease = isMultiRelease(jar);
        // Insertion-ordered so that two entries claiming the same resource name resolve the same
        // way on every run - jar order, as before this class existed.
        Map<String, JarEntry> chosen = new LinkedHashMap<>();
        Map<String, Integer> chosenRelease = new HashMap<>();

        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            String name = entry.getName();
            int release = 0;
            if (name.startsWith(VERSIONS_PREFIX)) {
                if (!multiRelease) {
                    continue;
                }
                int slash = name.indexOf('/', VERSIONS_PREFIX.length());
                if (slash < 0) {
                    continue; // META-INF/versions/something.class: not a versioned class at all
                }
                release = parseRelease(name.substring(VERSIONS_PREFIX.length(), slash));
                if (release < LOWEST_VERSIONED_RELEASE || release > targetRelease) {
                    continue;
                }
                name = name.substring(slash + 1);
            }
            Integer current = chosenRelease.get(name);
            if (current == null || current.intValue() < release) {
                chosenRelease.put(name, Integer.valueOf(release));
                chosen.put(name, entry);
            }
        }
        return chosen;
    }

    /** Whether the manifest opts the archive into versioned entries. */
    private static boolean isMultiRelease(JarFile jar) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return false;
        }
        String value = manifest.getMainAttributes().getValue("Multi-Release");
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /** The directory name, or -1 for anything that is not a plain release number. */
    private static int parseRelease(String directory) {
        if (directory.isEmpty()) {
            return -1;
        }
        int release = 0;
        for (int i = 0; i < directory.length(); i++) {
            char c = directory.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            release = release * 10 + (c - '0');
            if (release > Short.MAX_VALUE) {
                return -1; // not a release number anybody will ever target
            }
        }
        return release;
    }
}
