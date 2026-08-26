package com.caderly.caderlyhr.documents;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.util.List;

// No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4).
public interface CompanyFileRepository extends TenantAwareRepository<CompanyFile> {

    List<CompanyFile> findAllByOrderByCreatedAtDesc();
}
