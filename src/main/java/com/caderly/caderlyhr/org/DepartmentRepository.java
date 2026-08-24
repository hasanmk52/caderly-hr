package com.caderly.caderlyhr.org;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface DepartmentRepository extends TenantAwareRepository<Department> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Drives the Division delete-or-archive guard (PRD §13.2): non-empty divisions archive. */
    long countByDivisionIdAndArchivedFalse(UUID divisionId);

    List<Department> findAllByArchivedFalseOrderByNameAsc();
}
