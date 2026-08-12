package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeManagerHistoryRepository extends TenantAwareRepository<EmployeeManagerHistory> {

    Optional<EmployeeManagerHistory> findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
            UUID employeeId);

    /**
     * Secondary sort by {@code createdAt} breaks ties between rows opened the same calendar day
     * (e.g. two manager reassignments within one transactionally-fast test or a same-day
     * correction) — {@code effectiveFrom} alone doesn't order those deterministically.
     */
    List<EmployeeManagerHistory> findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(UUID employeeId);
}
