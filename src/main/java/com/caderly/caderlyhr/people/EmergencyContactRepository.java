package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public interface EmergencyContactRepository extends TenantAwareRepository<EmergencyContact> {

    List<EmergencyContact> findAllByEmployeeIdOrderByNameAsc(UUID employeeId);
}
