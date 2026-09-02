package com.staticallocationchecker.runtime;

import java.beans.ConstructorProperties;
import java.util.Objects;

/**
 * An immutable snapshot of the allocations recorded at a single site.
 *
 * <p>Exposed through JMX, so it is a plain JavaBean with a {@link ConstructorProperties}-annotated
 * constructor that the platform can map to and from {@code CompositeData}.
 */
public final class SiteRecord {

    private final long count;
    private final long firstSeenMillis;
    private final long lastSeenMillis;

    @ConstructorProperties({"count", "firstSeenMillis", "lastSeenMillis"})
    public SiteRecord(long count, long firstSeenMillis, long lastSeenMillis) {
        this.count = count;
        this.firstSeenMillis = firstSeenMillis;
        this.lastSeenMillis = lastSeenMillis;
    }

    public long count() {
        return count;
    }

    public long firstSeenMillis() {
        return firstSeenMillis;
    }

    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    /* JavaBean getters for JMX CompositeData mapping. */

    public long getCount() {
        return count;
    }

    public long getFirstSeenMillis() {
        return firstSeenMillis;
    }

    public long getLastSeenMillis() {
        return lastSeenMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SiteRecord)) {
            return false;
        }
        SiteRecord other = (SiteRecord) o;
        return count == other.count
                && firstSeenMillis == other.firstSeenMillis
                && lastSeenMillis == other.lastSeenMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, firstSeenMillis, lastSeenMillis);
    }

    @Override
    public String toString() {
        return "SiteRecord[count=" + count
                + ", firstSeenMillis=" + firstSeenMillis
                + ", lastSeenMillis=" + lastSeenMillis + "]";
    }
}
