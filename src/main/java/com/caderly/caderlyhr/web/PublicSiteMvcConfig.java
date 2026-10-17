package com.caderly.caderlyhr.web;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Routes base-domain requests to {@link PublicSiteController}s (ADR 0022 decision B) by giving
 * their mappings a {@link PublicSiteRequestCondition}. Done through Boot's {@link
 * WebMvcRegistrations} hook so Boot still configures the handler mapping exactly as before.
 */
@Configuration(proxyBeanMethods = false)
class PublicSiteMvcConfig {

    @Bean
    WebMvcRegistrations publicSiteHandlerMappingRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                // The method-level hook, not getCustomTypeCondition: Spring only builds (and asks for
                // a type condition on) a class-level mapping when the class itself carries
                // @RequestMapping, which the public-site controllers don't.
                return new RequestMappingHandlerMapping() {
                    @Override
                    protected @Nullable RequestCondition<?> getCustomMethodCondition(Method method) {
                        return AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), PublicSiteController.class)
                                ? PublicSiteRequestCondition.INSTANCE
                                : null;
                    }
                };
            }
        };
    }
}
