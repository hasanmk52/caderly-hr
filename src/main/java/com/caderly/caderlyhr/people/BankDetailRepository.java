package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface BankDetailRepository extends TenantAwareRepository<BankDetail> {

    Optional<BankDetail> findByEmployeeId(UUID employeeId);
}
