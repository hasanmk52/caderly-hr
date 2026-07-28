package com.helyx.helyxhr.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** Base for all entities: UUID v7 primary key (time-ordered, PRD §21) plus audit timestamps. */
@MappedSuperclass
public abstract class BaseEntity {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private @Nullable UUID id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private @Nullable Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private @Nullable Instant updatedAt;

  public @Nullable UUID getId() {
    return id;
  }

  public @Nullable Instant getCreatedAt() {
    return createdAt;
  }

  public @Nullable Instant getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public final boolean equals(Object other) {
    // Identity is the persistent id; unsaved entities (id null) are only equal to
    // themselves. instanceof (not getClass) so Hibernate proxies compare correctly.
    if (this == other) {
      return true;
    }
    return other instanceof BaseEntity that && id != null && id.equals(that.id);
  }

  @Override
  public final int hashCode() {
    // Constant per class, not per id: the id may be assigned after the entity has
    // entered a hash-based collection.
    return getClass().hashCode();
  }
}
