package com.staticallocationchecker.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or every method of a type) as being on a zero-allocation path.
 *
 * <p>The static allocation checker analyses the bytecode of an annotated method
 * and every method it calls transitively, reporting any heap allocation. Allocations
 * of {@link Throwable} subtypes are exempt (exceptional paths are not the hot path),
 * as are allocations reached through an {@link AllocationsForWarmup} boundary.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ZeroAllocations {
}
