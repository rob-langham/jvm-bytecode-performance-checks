package com.staticallocationchecker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or every method of a type) as a warmup boundary in which allocation
 * is permitted under a contract.
 *
 * <p>Each allocation inside an annotated method is compliant only if it is both
 * (1) guarded &mdash; control-dependent on a conditional branch, so some path skips it &mdash;
 * and (2) cached: the allocated reference flows into an instance or static field.
 * Non-compliant allocations are reported. When a {@link ZeroAllocations} walk reaches an
 * annotated method it stops descending and treats compliant warmup allocations as allowed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AllocationsForWarmup {
}
