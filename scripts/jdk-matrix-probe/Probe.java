package probe;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * Compiled inside the JDK 8 and 11 matrix containers by that era's real javac - not a modern
 * javac with --release, whose output can differ in shape. Proves two things at once: the
 * annotations in the core jar are acceptable to an actual Java 8 compiler, and the checker finds
 * the era's bytecode patterns (on javac 8 the concatenation below is a StringBuilder chain).
 */
public class Probe {

    @ZeroAllocations
    long hot(long id, int size) {
        Long boxed = id;
        String label = "tick " + id;
        Object marker = new Object();
        return boxed + label.length() + marker.hashCode() + size;
    }
}
