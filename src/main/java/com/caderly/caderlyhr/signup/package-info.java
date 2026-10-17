/**
 * Self-serve sign-up from the public site (ADR 0022): hold an address, confirm the email, then
 * provision the workspace through {@code superadmin.TenantProvisioningService}.
 *
 * <p>Sits beside {@code superadmin}, above {@code tenant}, {@code identity} and {@code
 * notifications}, and is depended on only by {@code web}.
 */
@NullMarked
package com.caderly.caderlyhr.signup;

import org.jspecify.annotations.NullMarked;
