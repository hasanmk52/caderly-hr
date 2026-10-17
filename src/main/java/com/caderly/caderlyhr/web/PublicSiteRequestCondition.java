package com.caderly.caderlyhr.web;

import com.caderly.caderlyhr.tenant.TenantResolutionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.condition.AbstractRequestCondition;

/**
 * Matches only base-domain requests (ADR 0022 decision B). Attached to every
 * {@link PublicSiteController} mapping by {@link PublicSiteMvcConfig}.
 *
 * <p>When a base-domain request matches both a public-site mapping and a tenant mapping for the
 * same path, Spring MVC prefers the mapping that carries a custom condition, so the public site
 * wins there; on a tenant host this condition doesn't match and only the tenant mapping is left.
 */
final class PublicSiteRequestCondition extends AbstractRequestCondition<PublicSiteRequestCondition> {

    static final PublicSiteRequestCondition INSTANCE = new PublicSiteRequestCondition();

    private PublicSiteRequestCondition() {}

    @Override
    protected List<String> getContent() {
        return List.of("publicSite");
    }

    @Override
    protected String getToStringInfix() {
        return "";
    }

    @Override
    public PublicSiteRequestCondition combine(PublicSiteRequestCondition other) {
        return this;
    }

    @Override
    public @Nullable PublicSiteRequestCondition getMatchingCondition(HttpServletRequest request) {
        return TenantResolutionFilter.isPublicSite(request) ? this : null;
    }

    @Override
    public int compareTo(PublicSiteRequestCondition other, HttpServletRequest request) {
        return 0;
    }
}
