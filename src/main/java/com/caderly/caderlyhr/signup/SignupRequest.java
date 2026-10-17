package com.caderly.caderlyhr.signup;

/** What the public sign-up form collects (ADR 0022 decision C). Validated by the web layer. */
public record SignupRequest(
        String companyName, String slug, String timezone, int weekendDays, String adminName, String adminEmail) {}
