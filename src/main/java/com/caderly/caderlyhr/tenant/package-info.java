/**
 * Tenant resolution and enforcement: the cross-tenant {@code tenant} root entity, request-scoped
 * tenant context, and the defense-in-depth plumbing (Hibernate {@code @TenantId} discriminator
 * multi-tenancy + Postgres RLS session variable) described in PRD §20 and ADR 0004.
 *
 * <p>This package deliberately imports nothing from {@code common}, and (since ADR 0004 replaced
 * {@code TenantAssignmentListener} with Hibernate's own {@code @TenantId} generation) {@code
 * common} no longer imports anything from here either — the two packages have no dependency on each
 * other in either direction.
 */
@NullMarked
package com.caderly.caderlyhr.tenant;

import org.jspecify.annotations.NullMarked;
