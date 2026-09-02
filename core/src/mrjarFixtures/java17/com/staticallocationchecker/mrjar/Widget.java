package com.staticallocationchecker.mrjar;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The release-17 copy of the multi-release fixture class, compiled at release 17 and placed under
 * {@code META-INF/versions/17} in the fixture jar.
 *
 * <p>Same name and same signature as the base copy, different body: this one allocates on the hot
 * path. A release-17 JVM loads this class and not the base one, so a checker told to target 17 must
 * report the finding - and a checker targeting 8 must not.
 */
public final class Widget {

    @ZeroAllocations
    public Object describe() {
        return new Object();
    }
}
