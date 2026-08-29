package com.caderly.caderlyhr.storage;

import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * File-bytes persistence, kept separate from the metadata row a caller stores in Postgres
 * (CLAUDE.md §11 — files never go in the database). A key is an opaque string the caller builds;
 * this interface does not interpret it beyond passing it straight to the backend.
 *
 * <p>{@link #presignedUrl(String)} is the seam a future S3-compatible implementation fills in: the
 * download controller checks it and redirects when present, streams via {@link #open(String)}
 * otherwise, so adding that implementation later touches no caller of this interface (PRD §6.7
 * FR-7.5, ADR 0012).
 */
public interface FileStorage {

    /** Writes {@code content} under {@code key}, overwriting whatever was there before. */
    void store(String key, InputStream content, long size, String contentType);

    /** @throws com.caderly.caderlyhr.common.NotFoundException if no file exists at {@code key}. */
    InputStream open(String key);

    /** No-op if nothing exists at {@code key} — deletion is idempotent. */
    void delete(String key);

    default Optional<URI> presignedUrl(String key) {
        return Optional.empty();
    }
}
