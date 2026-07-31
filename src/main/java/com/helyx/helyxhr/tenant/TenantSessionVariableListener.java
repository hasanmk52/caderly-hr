package com.helyx.helyxhr.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * RLS defense-in-depth (PRD §20.4, CLAUDE.md §5 rule 4a): sets the Postgres {@code app.tenant_id}
 * session variable at the start of every transaction, independent of Hibernate's own
 * {@code @TenantId} filtering (see {@link TenantIdentifierResolver}). Not an AOP aspect (see ADR
 * 0004): Spring Boot's transaction-manager autoconfiguration discovers every {@code
 * TransactionExecutionListener} bean and registers it on the transaction manager itself, so no
 * manual registration or {@code @Order} coordination is needed here.
 */
@Component
class TenantSessionVariableListener implements TransactionExecutionListener {

    @PersistenceContext private EntityManager entityManager;

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        if (TenantContext.isSystem()) {
            // Audited bypass (CLAUDE.md §5 rule 6): no app-layer session variable; RLS still applies.
            return;
        }
        UUID tenantId = TenantContext.require();
        Session session = entityManager.unwrap(Session.class);
        // set_config(..., true) = transaction-local (SET LOCAL): the setting dies with the
        // transaction, so a pooled connection can never carry a tenant into the next request.
        session.doWork(
                connection -> {
                    try (PreparedStatement statement =
                                 connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        statement.setString(1, tenantId.toString());
                        statement.execute();
                    }
                });
    }
}
