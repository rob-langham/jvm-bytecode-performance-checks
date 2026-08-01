package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.AllocationsForWarmup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fixture: the ways a warmup allocation can be retained, beyond a direct field store. */
public class WarmupCaching {

    private static Object staticCache;

    private Object instanceCache;
    private Object[] pool;
    private final List<Object> list = new ArrayList<>();
    private final Map<String, Object> map = new HashMap<>();

    /** Guarded, stored into a static field. */
    @AllocationsForWarmup
    public Object staticField() {
        if (staticCache == null) {
            staticCache = new Object();
        }
        return staticCache;
    }

    /** Guarded, stored into an instance field via a local variable round-trip. */
    @AllocationsForWarmup
    public Object throughLocal() {
        if (instanceCache == null) {
            Object created = new Object();
            instanceCache = created;
        }
        return instanceCache;
    }

    /** Guarded, retained by adding to a field-held collection rather than by a field store. */
    @AllocationsForWarmup
    public void intoCollection(boolean init) {
        if (init) {
            list.add(new Object());
        }
    }

    /** Guarded, retained by putting into a field-held map. */
    @AllocationsForWarmup
    public void intoMap(boolean init) {
        if (init) {
            map.put("k", new Object());
        }
    }

    /** Guarded; the array is cached in a field but its elements are stored via AASTORE. */
    @AllocationsForWarmup
    public void intoArrayElements(int n) {
        if (pool == null) {
            pool = new Object[n];
            for (int i = 0; i < n; i++) {
                pool[i] = new Object();
            }
        }
    }

    /** Guarded and cached, but the guard is a loop rather than an if. */
    @AllocationsForWarmup
    public void guardedByLoop(int n) {
        for (int i = 0; i < n; i++) {
            instanceCache = new Object();
        }
    }

    /** Guarded by a ternary rather than a statement-level branch. */
    @AllocationsForWarmup
    public Object guardedByTernary() {
        instanceCache = instanceCache == null ? new Object() : instanceCache;
        return instanceCache;
    }

    /** Guarded and cached, with the allocation inside a try block. */
    @AllocationsForWarmup
    public Object insideTryBlock() {
        if (instanceCache == null) {
            try {
                instanceCache = new Object();
            } catch (RuntimeException e) {
                instanceCache = null;
            }
        }
        return instanceCache;
    }

    /** Two independent compliant allocations in one method. */
    @AllocationsForWarmup
    public void twoCompliantAllocations() {
        if (instanceCache == null) {
            instanceCache = new Object();
        }
        if (staticCache == null) {
            staticCache = new Object();
        }
    }
}
