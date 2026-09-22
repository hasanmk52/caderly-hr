package com.caderly.caderlyhr.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code HttpSession} remembers about a Super Admin support session (PRD FR-1.8), beyond
 * the {@code SecurityContext} itself.
 *
 * <p>One attribute holding one record rather than three loose attributes: the three values are
 * only ever read together, and the correlation id in particular is meaningless without the other
 * two — it is what pairs the {@code CREATE} {@code audit_entry} row written at redemption with the
 * {@code DELETE} row written when the session ends.
 *
 * <p>Its presence is also the single answer to "is this an impersonated session", read by {@link
 * ImpersonationModelAdvice} for the banner and by {@link ImpersonationController} when ending one.
 * The {@code SecurityContext}'s principal type would answer the same question, but only for code
 * that can see the {@code identity} package; the session attribute keeps that out of the view
 * layer.
 *
 * <p>{@link Serializable} because sessions are serialized by some containers on restart. The
 * session store is in-JVM today (PRD §19.7), so this costs nothing and removes a trap later.
 */
record ImpersonationSession(
        UUID superAdminId, String superAdminEmail, String impersonatedEmail, String correlationId)
        implements Serializable {

    static final String ATTRIBUTE = "CADERLY_IMPERSONATION";

    void storeIn(HttpSession session) {
        session.setAttribute(ATTRIBUTE, this);
    }

    /** The support session this request belongs to, or {@code null} for an ordinary tenant login. */
    static @Nullable ImpersonationSession of(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        return session.getAttribute(ATTRIBUTE) instanceof ImpersonationSession impersonation
                ? impersonation
                : null;
    }
}
