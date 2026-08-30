package com.caderly.caderlyhr.timeoff;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TimeoffFacadeImpl implements TimeoffFacade {

    private final LeaveRequestRepository leaveRequests;
    private final PublicHolidayRepository holidays;

    TimeoffFacadeImpl(LeaveRequestRepository leaveRequests, PublicHolidayRepository holidays) {
        this.leaveRequests = leaveRequests;
        this.holidays = holidays;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovedLeaveEntry> listApprovedLeaveInRange(
            LocalDate from, LocalDate to, List<UUID> employeeIds, @Nullable UUID leaveTypeId) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return leaveRequests.findApprovedInRangeForEmployees(employeeIds, from, to, leaveTypeId).stream()
                .map(TimeoffFacadeImpl::toEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovedLeaveEntry> listAllApprovedLeaveForEmployee(UUID employeeId) {
        return leaveRequests
                .findAllByEmployeeIdAndStatusOrderByStartDateAsc(employeeId, LeaveRequestStatus.APPROVED)
                .stream()
                .map(TimeoffFacadeImpl::toEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HolidayMarker> listPublicHolidaysInRange(LocalDate from, LocalDate to) {
        return holidays.findAllByOrderByDateAsc().stream()
                .filter(h -> !h.date().isBefore(from) && !h.date().isAfter(to))
                .map(h -> new HolidayMarker(h.date(), h.name()))
                .toList();
    }

    private static ApprovedLeaveEntry toEntry(LeaveRequest request) {
        LeaveType type = request.leaveType();
        return new ApprovedLeaveEntry(
                request.employeeId(),
                request.requireId(),
                type.name(),
                type.color(),
                type.icon(),
                request.startDate(),
                request.endDate(),
                request.startHalfDayPm(),
                request.endHalfDayAm(),
                request.durationDays());
    }
}
