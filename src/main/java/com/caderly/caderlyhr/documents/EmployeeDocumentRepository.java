package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;

// No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4).
public interface EmployeeDocumentRepository extends TenantAwareRepository<EmployeeDocument> {

    List<EmployeeDocument> findAllByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);
}
