/**
 * The Super Admin console (PRD FR-1.8, Phase 1.13): a cross-tenant operator realm with its own
 * login, entity, and security context, entirely separate from {@code identity}'s per-tenant
 * users.
 *
 * <p>Every database call reachable from this package must run under {@code
 * tenant.TenantContext#runAsSystem} (Global Constraint 1 of the Phase 1.13 execution plan): {@code
 * tenant.TenantSessionVariableListener} fires on every transaction regardless of which table it
 * touches, and {@code /superadmin/**} requests never pass through {@code
 * tenant.TenantResolutionFilter}, so {@code TenantContext} is always empty on this realm's
 * threads — even a lookup on {@code super_admin}, a table with no RLS at all.
 */
@NullMarked
package com.caderly.caderlyhr.superadmin;

import org.jspecify.annotations.NullMarked;
