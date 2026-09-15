package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

@Repository
public interface GovernmentIdRepository extends TenantAwareRepository<GovernmentId> {

    List<GovernmentId> findAllByEmployeeIdOrderByIdTypeAsc(UUID employeeId);

    /**
     * IDs expiring on any of the given dates — the 30/14/7-day reminder windows (PRD FR-3.7,
     * §17.2). Exact dates rather than a range so an ID is flagged three times, not every day for
     * a month. The employee is fetched eagerly: the reminder needs their name and work address,
     * and the {@code @ManyToOne} here is LAZY.
     */
    @EntityGraph(attributePaths = "employee")
    List<GovernmentId> findAllByExpiryDateIn(Collection<LocalDate> dates);
}
