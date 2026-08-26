/**
 * File-bytes storage abstraction (PRD §6.7 FR-7.5, CLAUDE.md §4): {@link
 * com.caderly.caderlyhr.storage.FileStorage} is local-filesystem today and can grow an
 * S3-compatible implementation later without touching {@code documents} or {@code web} (ADR 0012).
 *
 * <p>Not tenant-scoped itself: it stores bytes under a key the caller ({@code documents}) builds
 * as {@code {tenantId}/{uuid}}, so tenancy is encoded in the key, not enforced here.
 */
@NullMarked
package com.caderly.caderlyhr.storage;

import org.jspecify.annotations.NullMarked;
