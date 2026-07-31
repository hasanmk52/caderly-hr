package com.helyx.helyxhr.tenantisolation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only service. Being a {@code @Service} in a tenant-scoped package, every call goes through
 * Hibernate's {@code @TenantId} discriminator multi-tenancy (ADR 0004) — exactly like any future
 * production service.
 */
@Service
public class IsolationProbeService {

    private final IsolationProbeRepository repository;

    IsolationProbeService(IsolationProbeRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IsolationProbe save(String label) {
        return repository.save(new IsolationProbe(label));
    }

    @Transactional(readOnly = true)
    public List<IsolationProbe> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<IsolationProbe> findById(UUID id) {
        return repository.findById(id);
    }

    // Realistic load-modify-save pattern (not a bulk update) - returns empty if the row
    // isn't visible under the current tenant, same contract as findById.
    @Transactional
    public Optional<IsolationProbe> relabel(UUID id, String newLabel) {
        return repository
                .findById(id)
                .map(
                        probe -> {
                            probe.relabel(newLabel);
                            return probe;
                        });
    }

    // Direct delete-by-id, deliberately with no prior findById - proves @TenantId scopes
    // this the same way, not just entity-loading paths.
    @Transactional
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        return repository.existsById(id);
    }
}
