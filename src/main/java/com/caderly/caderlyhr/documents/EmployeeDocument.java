package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A document on one employee's profile (PRD FR-3.9, §6.7 FR-7.2). {@code employeeId} is a plain
 * id, not a JPA relation — same reasoning as {@code timeoff.LeaveRequest#employeeId}: {@code
 * documents} never depends on {@code people} directly (CLAUDE.md §4).
 *
 * <p>No public setters; the two static factories mirror the two ways a row can come to exist —
 * {@link #uploadOwn} always fixes {@link DocumentVisibility#EMPLOYEE_PRIVATE} with no visibility
 * parameter at all, so a self-upload request body has no field that could tamper with it. Only
 * {@link #uploadOnBehalf} (Admin-only, enforced by the caller) accepts a caller-chosen visibility.
 */
@Entity
@Table(name = "employee_document")
public class EmployeeDocument extends TenantAwareEntity {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "mime", nullable = false, length = 100)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private DocumentVisibility visibility;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    protected EmployeeDocument() {}

    private EmployeeDocument(
            UUID employeeId,
            String name,
            String mime,
            long sizeBytes,
            String storageKey,
            DocumentVisibility visibility,
            UUID uploadedBy) {
        this.employeeId = employeeId;
        this.name = name;
        this.mime = mime;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.visibility = visibility;
        this.uploadedBy = uploadedBy;
    }

    public static EmployeeDocument uploadOwn(
            UUID employeeId, String name, String mime, long sizeBytes, String storageKey, UUID uploaderUserId) {
        return new EmployeeDocument(
                employeeId,
                name,
                mime,
                sizeBytes,
                storageKey,
                DocumentVisibility.EMPLOYEE_PRIVATE,
                uploaderUserId);
    }

    public static EmployeeDocument uploadOnBehalf(
            UUID employeeId,
            String name,
            String mime,
            long sizeBytes,
            String storageKey,
            DocumentVisibility visibility,
            UUID uploadedBy) {
        return new EmployeeDocument(employeeId, name, mime, sizeBytes, storageKey, visibility, uploadedBy);
    }

    public UUID employeeId() {
        return employeeId;
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

    public DocumentVisibility visibility() {
        return visibility;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }

    public @Nullable Instant uploadedAt() {
        return getCreatedAt();
    }
}
