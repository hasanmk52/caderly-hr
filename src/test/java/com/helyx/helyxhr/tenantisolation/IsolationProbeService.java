package com.helyx.helyxhr.tenantisolation;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only service. Being a {@code @Service} in a tenant-scoped package, every call goes through
 * TenantEnforcementAspect — exactly like any future production service.
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
}
