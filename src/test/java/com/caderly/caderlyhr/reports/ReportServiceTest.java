package com.caderly.caderlyhr.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.caderly.caderlyhr.people.EmployeeStatus;
import com.caderly.caderlyhr.people.PeopleFacade;
import com.caderly.caderlyhr.people.PeopleFacade.EmployeeReportInfo;
import com.caderly.caderlyhr.people.PeopleFacade.MonthlyHeadcount;
import com.caderly.caderlyhr.timeoff.TimeoffFacade;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.EmployeeBalanceInfo;
import com.caderly.caderlyhr.timeoff.TimeoffFacade.UtilizationSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ReportService} only joins and filters what {@link PeopleFacade}/{@link TimeoffFacade}
 * already aggregated (ADR 0018) — this suite mocks both facades so the join/filter logic itself
 * is what's under test, not the aggregation queries ({@code PeopleFacadeImplTest} and {@code
 * TimeoffFacadeImplTest} cover those against a real database).
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock private PeopleFacade people;
    @Mock private TimeoffFacade timeoff;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(people, timeoff);
    }

    @Test
    void leaveBalanceReport_joinsBalanceToEmployeeByEmployeeId() {
        UUID employeeId = UUID.randomUUID();
        UUID leaveTypeId = UUID.randomUUID();
        when(people.listEmployeesForReport(any(), any(), any()))
                .thenReturn(
                        List.of(new EmployeeReportInfo(employeeId, "Jane Doe", null, "Engineering", "Tech", EmployeeStatus.ACTIVE)));
        when(timeoff.listBalancesForYear(anyInt(), any()))
                .thenReturn(
                        List.of(
                                new EmployeeBalanceInfo(
                                        employeeId,
                                        leaveTypeId,
                                        "Vacation",
                                        new BigDecimal("20"),
                                        new BigDecimal("5"),
                                        new BigDecimal("15"))));

        List<ReportService.LeaveBalanceRow> rows =
                reportService.leaveBalanceReport(null, null, null, null, 2026);

        assertThat(rows).hasSize(1);
        ReportService.LeaveBalanceRow row = rows.get(0);
        assertThat(row.employeeName()).isEqualTo("Jane Doe");
        assertThat(row.departmentName()).isEqualTo("Engineering");
        assertThat(row.leaveTypeName()).isEqualTo("Vacation");
        assertThat(row.granted()).isEqualByComparingTo("20");
        assertThat(row.used()).isEqualByComparingTo("5");
        assertThat(row.remaining()).isEqualByComparingTo("15");
    }

    @Test
    void leaveBalanceReport_dropsBalancesForEmployeesTheFilterExcluded() {
        UUID filteredOutEmployeeId = UUID.randomUUID();
        // people.listEmployeesForReport already applied the department/division/status filter —
        // an employee id in the balance list that isn't in the employee list means that employee
        // didn't match, so the row must be dropped, not shown with a blank department.
        when(people.listEmployeesForReport(any(), any(), any())).thenReturn(List.of());
        when(timeoff.listBalancesForYear(anyInt(), any()))
                .thenReturn(
                        List.of(
                                new EmployeeBalanceInfo(
                                        filteredOutEmployeeId,
                                        UUID.randomUUID(),
                                        "Vacation",
                                        BigDecimal.TEN,
                                        BigDecimal.ZERO,
                                        BigDecimal.TEN)));

        List<ReportService.LeaveBalanceRow> rows =
                reportService.leaveBalanceReport(UUID.randomUUID(), null, null, null, 2026);

        assertThat(rows).isEmpty();
    }

    @Test
    void leaveUtilizationReport_joinsSummaryToEmployeeByEmployeeId() {
        UUID employeeId = UUID.randomUUID();
        when(people.listEmployeesForReport(any(), any(), any()))
                .thenReturn(
                        List.of(new EmployeeReportInfo(employeeId, "Priya Shah", null, "Sales", "Revenue", EmployeeStatus.ACTIVE)));
        when(timeoff.summarizeUtilization(any(), any(), any()))
                .thenReturn(
                        List.of(
                                new UtilizationSummary(
                                        employeeId, UUID.randomUUID(), "Sick", new BigDecimal("3.00"), 2L)));

        List<ReportService.UtilizationRow> rows =
                reportService.leaveUtilizationReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null);

        assertThat(rows).hasSize(1);
        ReportService.UtilizationRow row = rows.get(0);
        assertThat(row.employeeName()).isEqualTo("Priya Shah");
        assertThat(row.leaveTypeName()).isEqualTo("Sick");
        assertThat(row.daysUsed()).isEqualByComparingTo("3.00");
        assertThat(row.requestCount()).isEqualTo(2L);
    }

    @Test
    void headcountReport_mapsMonthlyHeadcountDirectlyWithoutJoining() {
        when(people.countActiveEmployeesByMonth(any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new MonthlyHeadcount(LocalDate.of(2026, 1, 31), UUID.randomUUID(), "Engineering", 12L)));

        List<ReportService.HeadcountRow> rows =
                reportService.headcountReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null);

        assertThat(rows).hasSize(1);
        ReportService.HeadcountRow row = rows.get(0);
        assertThat(row.month()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(row.departmentName()).isEqualTo("Engineering");
        assertThat(row.activeCount()).isEqualTo(12L);
    }
}
