/**
 * Authentication, authorization, and the request filters that support them.
 *
 * <p>Depends on {@code identity} only through Spring Security's own {@code UserDetailsService}
 * contract and the {@code LoginAttemptService} facade, so the two packages stay acyclic.
 *
 * <p>{@code SecurityConfig} is on the CLAUDE.md §12 ask-first list. Changes to it need an ADR
 * update, not just a code review.
 */
@NullMarked
package com.helyx.helyxhr.security;

import org.jspecify.annotations.NullMarked;
