package com.caderly.caderlyhr.audit;

import com.caderly.caderlyhr.audit.system.AuditEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Plain {@link JpaRepository}, not {@code TenantAwareRepository}: {@link AuditEntry} is
 * system-scoped (ADR 0005 decision B), so there is no tenant restriction to inherit. {@link
 * AuditAdminService} is the only permitted caller of the {@link JpaSpecificationExecutor} half —
 * it supplies the tenant predicate this table has no RLS policy to supply for it.
 *
 * <p>Rows are inserted by {@link EntityAuditListener} via raw JDBC, never through this interface's
 * {@code save(...)} — see that class's Javadoc.
 */
@Repository
public interface AuditEntryRepository
        extends JpaRepository<AuditEntry, UUID>, JpaSpecificationExecutor<AuditEntry> {}
