package com.caderly.caderlyhr.people;

import com.caderly.caderlyhr.common.TenantAwareRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeStatusHistoryRepository extends TenantAwareRepository<EmployeeStatusHistory> {

    Optional<EmployeeStatusHistory> findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(
            UUID employeeId);

    /** Secondary sort by {@code createdAt} breaks ties between rows opened the same calendar day. */
    List<EmployeeStatusHistory> findAllByEmployeeIdOrderByEffectiveFromDescCreatedAtDesc(UUID employeeId);

    /**
     * {@code reports.ReportService}'s Headcount report (PRD §16.1 FR-10.3), one call per month in
     * the requested range ({@code people.PeopleFacadeImpl#countActiveEmployeesByMonth}). One
     * history row covers at most one open period per employee, so {@code COUNT(DISTINCT e.id)}
     * and a plain row count agree here — {@code DISTINCT} is defence, not load-bearing.
     *
     * <p>{@code LEFT JOIN e.department}, not the implicit inner-join path style {@link
     * EmployeeRepository#findForReport} uses in a {@code WHERE}-only predicate: this query
     * <em>projects</em> the department, so an employee with none must still produce a row (grouped
     * under a {@code null} department) rather than being silently dropped — the first GROUP BY
     * query in the codebase (ADR 0018).
     *
     * <p>Returns {@code Object[]}, not a constructor-expression DTO: {@code
     * PeopleFacadeImpl} already needs to attach the calendar month label the query itself
     * has no notion of, so it maps this tuple to {@link PeopleFacade.MonthlyHeadcount} anyway.
     */
    @Query(
            """
            SELECT d.id, d.name, COUNT(DISTINCT e.id)
            FROM EmployeeStatusHistory h
            JOIN h.employee e
            LEFT JOIN e.department d
            WHERE h.status = com.caderly.caderlyhr.people.EmployeeStatus.ACTIVE
              AND h.effectiveFrom <= :asOf
              AND (h.effectiveTo IS NULL OR h.effectiveTo > :asOf)
              AND (:employmentType IS NULL OR h.employmentType = :employmentType)
              AND (:departmentId IS NULL OR d.id = :departmentId)
            GROUP BY d.id, d.name
            """)
    List<Object[]> countActiveByDepartmentAsOf(
            @Param("asOf") LocalDate asOf,
            @Param("employmentType") @Nullable EmploymentType employmentType,
            @Param("departmentId") @Nullable UUID departmentId);
}
