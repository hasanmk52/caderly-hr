package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface BankDetailRepository extends TenantAwareRepository<BankDetail> {

    Optional<BankDetail> findByEmployeeId(UUID employeeId);
}
