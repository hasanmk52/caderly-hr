package com.caderly.caderlyhr.common;

import java.util.Set;
import java.util.UUID;

/**
 * What {@code audit.EntityAuditListener} needs from an authenticated principal in order to attribute a
 * write. Implemented by {@code identity.AppUserPrincipal}.
 *
 * <p>Lives in {@code common} — the one package every module already depends on — specifically so
 * {@code audit} never has to depend on {@code identity} to read it. {@code identity} depends on
 * {@code audit} instead (the (email + IP) lockout re-key needs {@code audit.LoginAuditRepository},
 * ADR 0017), and a dependency the other way would be a package cycle.
 */
public interface AuditActor {

    UUID actorId();

    /** Role names as Spring Security sees them (e.g. {@code "ADMIN"}), never the {@code ROLE_}-prefixed authority string. */
    Set<String> roleNames();
}
