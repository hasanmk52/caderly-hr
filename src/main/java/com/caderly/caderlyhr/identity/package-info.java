/**
 * Identity and access: users, their roles, and the invite / password-reset token flows.
 *
 * <p>Every entity here is tenant-scoped (CLAUDE.md §5). The package exposes {@code
 * AppUserDetailsService} to Spring Security purely through the framework's {@code
 * UserDetailsService} interface, so {@code security} depends on no type declared here and the two
 * packages stay acyclic.
 */
@NullMarked
package com.caderly.caderlyhr.identity;

import org.jspecify.annotations.NullMarked;
