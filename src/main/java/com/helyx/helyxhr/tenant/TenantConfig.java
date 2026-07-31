package com.helyx.helyxhr.tenant;

import java.util.Map;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Wires tenant resolution and enforcement (CLAUDE.md §5). */
@Configuration(proxyBeanMethods = false)
class TenantConfig {

    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilterRegistration(
            TenantFacade tenants, @Value("${helyx.base-domain:localhost}") String baseDomain) {
        var registration =
                new FilterRegistrationBean<>(new TenantResolutionFilter(tenants, baseDomain));
        // First in the chain (PRD §20.3): tenant must be resolved before security or MVC run.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    // Registers TenantIdentifierResolver as the resolver Hibernate's @TenantId discriminator
    // multi-tenancy calls to find the current tenant (CLAUDE.md §5 rule 4).
    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(
            TenantIdentifierResolver resolver) {
        return (Map<String, Object> properties) ->
                properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
