package com.caderly.caderlyhr.timeoff;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

/** No method here mentions tenant_id, and none may (CLAUDE.md §5 rule 4). */
@Repository
public interface PublicHolidayRepository extends TenantAwareRepository<PublicHoliday> {

    List<PublicHoliday> findAllByOrderByDateAsc();

    boolean existsByDateAndNameIgnoreCase(LocalDate date, String name);
}
