/**
 * Server-rendered Thymeleaf controllers (ADR 0002).
 *
 * <p>Every controller method carries an explicit {@code @PreAuthorize} — including the public
 * ones, which use {@code permitAll()} (CLAUDE.md §6 A01). {@code ArchitectureTest} enforces this,
 * so access for a new endpoint is a decision someone has to write down rather than something
 * inherited from a URL list in {@code SecurityConfig}.
 */
@NullMarked
package com.helyx.helyxhr.web;

import org.jspecify.annotations.NullMarked;
