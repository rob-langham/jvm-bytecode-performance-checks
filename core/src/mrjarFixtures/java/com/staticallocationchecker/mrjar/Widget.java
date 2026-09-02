package com.staticallocationchecker.mrjar;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The base copy of a multi-release fixture class, compiled at release 8 and placed at the root of
 * the fixture jar. It allocates nothing on its hot path.
 *
 * <p>Its companion under {@code src/mrjarFixtures/java17} carries the same fully qualified name and
 * the same signature, and does allocate. Which of the two the checker reports on is the whole
 * subject of {@code MultiReleaseFixtureJarTest}.
 */
public final class Widget {

    private final Object cached = new Object();

    @ZeroAllocations
    public Object describe() {
        return cached;
    }
}
