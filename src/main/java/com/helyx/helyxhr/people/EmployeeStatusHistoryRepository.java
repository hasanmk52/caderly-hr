package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeStatusHistoryRepository extends TenantAwareRepository<EmployeeStatusHistory> {

    Optional<EmployeeStatusHistory> findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
            UUID employeeId);

    /** Secondary sort by {@code createdAt} breaks ties between rows opened the same calendar day. */
    List<EmployeeStatusHistory> findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(UUID employeeId);
}
