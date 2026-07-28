package com.helyx.helyxhr.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires tenant resolution and enforcement. {@code order = 0} on transaction management pins the
 * transaction advisor ahead of {@link TenantEnforcementAspect} (order 100), so the aspect always
 * runs INSIDE the transaction — its SET LOCAL and session filter must bind to the transactional
 * connection, not a throwaway one.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = 0)
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
}
