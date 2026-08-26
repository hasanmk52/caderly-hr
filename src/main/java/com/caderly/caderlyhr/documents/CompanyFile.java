package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tenant-wide file, uploaded by an Admin and visible to everyone in the tenant (PRD §6.7 FR-7.1).
 * No separate {@code uploaded_at} column: {@link #getCreatedAt()} (from {@link
 * com.caderly.caderlyhr.common.BaseEntity}) already means exactly that for an entity nothing ever
 * re-uploads in place, so {@link #uploadedAt()} just names it for the reader — matching every
 * other entity's convention of not duplicating an audit timestamp under a domain-specific name.
 *
 * <p>Stores metadata only, never bytes (CLAUDE.md §11) — {@code storageKey} is what {@code
 * storage.FileStorage} was given to persist the actual content.
 */
@Entity
@Table(name = "company_file")
public class CompanyFile extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "mime", nullable = false, length = 100)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    protected CompanyFile() {}

    private CompanyFile(String name, String mime, long sizeBytes, String storageKey, UUID uploadedBy) {
        this.name = name;
        this.mime = mime;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
    }

    public static CompanyFile create(String name, String mime, long sizeBytes, String storageKey, UUID uploadedBy) {
        return new CompanyFile(name, mime, sizeBytes, storageKey, uploadedBy);
    }

    public String name() {
        return name;
    }

    public String mime() {
        return mime;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String storageKey() {
        return storageKey;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }

    public @Nullable Instant uploadedAt() {
        return getCreatedAt();
    }
}
