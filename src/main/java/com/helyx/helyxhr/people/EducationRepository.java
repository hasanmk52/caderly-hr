package com.helyx.helyxhr.people;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface EducationRepository extends TenantAwareRepository<Education> {

    List<Education> findAllByEmployeeIdOrderByStartYearDesc(UUID employeeId);
}
