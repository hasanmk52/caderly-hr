/**
 * Tenant resolution and enforcement: the cross-tenant {@code tenant} root entity, request-scoped
 * tenant context, and the defense-in-depth plumbing (Hibernate filter + Postgres RLS session
 * variable) described in PRD §20.
 *
 * <p>This package deliberately imports nothing from {@code common}: {@code common} depends on it
 * (TenantAssignmentListener → TenantContext), and the reverse edge would create a package cycle.
 */
@NullMarked
package com.helyx.helyxhr.tenant;

import org.jspecify.annotations.NullMarked;
