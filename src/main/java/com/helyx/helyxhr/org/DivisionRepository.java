package com.helyx.helyxhr.org;

import com.helyx.helyxhr.common.TenantAwareRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface DivisionRepository extends TenantAwareRepository<Division> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    List<Division> findAllByArchivedFalseOrderByNameAsc();
}
