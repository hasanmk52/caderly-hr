package com.caderly.caderlyhr.reports;

import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.people.EmploymentType;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeReportInfo;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.EmployeeBalanceInfo;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.UtilizationSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the three Admin → Reports pages (PRD §16.1, Phase 1.12). Every method only joins and
 * filters data that {@link PeopleFacade}/{@link TimeoffFacade} already aggregated — the
 * aggregation itself (SUM/COUNT/GROUP BY) lives in the module that owns the underlying entity
 * (ADR 0018), never here.
 */
@Service
public class ReportService {

    private final PeopleFacade people;
    private final TimeoffFacade timeoff;

    ReportService(PeopleFacade people, TimeoffFacade timeoff) {
        this.people = people;
        this.timeoff = timeoff;
    }

    /** PRD §16.1 "Leave Balance": one row per (employee, leave type) for {@code year}. */
    @Transactional(readOnly = true)
    public List<LeaveBalanceRow> leaveBalanceReport(
            @Nullable UUID departmentId,
            @Nullable UUID divisionId,
            @Nullable EmployeeStatus status,
            @Nullable UUID leaveTypeId,
            int year) {
        Map<UUID, EmployeeReportInfo> employeesById = indexById(
                people.listEmployeesForReport(departmentId, divisionId, status), EmployeeReportInfo::employeeId);
        List<EmployeeBalanceInfo> balances = timeoff.listBalancesForYear(year, leaveTypeId);

        return balances.stream()
                .map(balance -> toBalanceRow(balance, employeesById.get(balance.employeeId())))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(LeaveBalanceRow::employeeName).thenComparing(LeaveBalanceRow::leaveTypeName))
                .toList();
    }

    /** PRD §16.1 "Leave Utilization": one row per (employee, leave type) within {@code [from, to]}. */
    @Transactional(readOnly = true)
    public List<UtilizationRow> leaveUtilizationReport(
            LocalDate from, LocalDate to, @Nullable UUID departmentId, @Nullable UUID leaveTypeId) {
        Map<UUID, EmployeeReportInfo> employeesById = indexById(
                people.listEmployeesForReport(departmentId, null, null), EmployeeReportInfo::employeeId);
        List<UtilizationSummary> summaries = timeoff.summarizeUtilization(from, to, leaveTypeId);

        return summaries.stream()
                .map(summary -> toUtilizationRow(summary, employeesById.get(summary.employeeId())))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(UtilizationRow::employeeName).thenComparing(UtilizationRow::leaveTypeName))
                .toList();
    }

    /** PRD §16.1 "Headcount": one row per (month, department) across {@code [from, to]}. */
    @Transactional(readOnly = true)
    public List<HeadcountRow> headcountReport(
            LocalDate from, LocalDate to, @Nullable UUID departmentId, @Nullable EmploymentType employmentType) {
        return people.countActiveEmployeesByMonth(from, to, departmentId, employmentType).stream()
                .map(
                        row ->
                                new HeadcountRow(row.month(), row.departmentName(), row.activeCount()))
                .sorted(
                        Comparator.comparing(HeadcountRow::month)
                                .thenComparing(HeadcountRow::departmentName, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * A balance whose employee didn't match the department/division/status filter is dropped —
     * {@code employeesById} only contains employees the caller actually asked for.
     */
    private static @Nullable LeaveBalanceRow toBalanceRow(
            EmployeeBalanceInfo balance, @Nullable EmployeeReportInfo employee) {
        if (employee == null) {
            return null;
        }
        return new LeaveBalanceRow(
                employee.fullName(),
                employee.departmentName(),
                balance.leaveTypeName(),
                balance.granted(),
                balance.used(),
                balance.remaining());
    }

    private static @Nullable UtilizationRow toUtilizationRow(
            UtilizationSummary summary, @Nullable EmployeeReportInfo employee) {
        if (employee == null) {
            return null;
        }
        return new UtilizationRow(
                employee.fullName(), employee.departmentName(), summary.leaveTypeName(), summary.daysUsed(), summary.requestCount());
    }

    private static <T> Map<UUID, T> indexById(List<T> items, Function<T, UUID> idExtractor) {
        return items.stream().collect(java.util.stream.Collectors.toMap(idExtractor, Function.identity()));
    }

    public record LeaveBalanceRow(
            String employeeName,
            @Nullable String departmentName,
            String leaveTypeName,
            BigDecimal granted,
            BigDecimal used,
            BigDecimal remaining) {}

    public record UtilizationRow(
            String employeeName,
            @Nullable String departmentName,
            String leaveTypeName,
            BigDecimal daysUsed,
            long requestCount) {}

    public record HeadcountRow(LocalDate month, @Nullable String departmentName, long activeCount) {}
}
