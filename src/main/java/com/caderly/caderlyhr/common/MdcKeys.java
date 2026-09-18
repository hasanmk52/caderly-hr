package com.caderly.caderlyhr.common;

/**
 * MDC key names shared between the filters that populate them ({@code
 * tenant.TenantResolutionFilter}, {@code web.ActorMdcFilter}) and the code that reads them back
 * ({@code audit.EntityAuditListener}, {@code audit.LoginAuditService}) — CLAUDE.md §6 A09's
 * request-id/tenant-id/actor-id correlation, ADR 0017. One place so the key string used to write
 * and the one used to read can never drift apart.
 */
public final class MdcKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";
    public static final String ACTOR_ID = "actorId";

    private MdcKeys() {}
}
