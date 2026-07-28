package com.helyx.helyxhr.tenantisolation;

import com.helyx.helyxhr.common.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Test-only entity proving the Definition of Done: extending {@link TenantAwareEntity} (plus the
 * RLS migration template) is ALL it takes for an entity to be tenant-safe — filter, RLS, and
 * tenant_id assignment happen with zero code here.
 */
@Entity
@Table(name = "isolation_probe")
public class IsolationProbe extends TenantAwareEntity {

  @Column(name = "label", nullable = false)
  private String label;

  protected IsolationProbe() {}

  public IsolationProbe(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
