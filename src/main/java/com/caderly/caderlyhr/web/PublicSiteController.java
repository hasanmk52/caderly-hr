package com.caderly.caderlyhr.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Controller;

/**
 * A controller that serves the public site on the base domain (ADR 0022 decision B). Its mappings
 * only match requests {@code tenant.TenantResolutionFilter} marked as public-site requests, which
 * lets it own {@code /} and {@code /login} without colliding with the tenant app's own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Controller
@interface PublicSiteController {}
