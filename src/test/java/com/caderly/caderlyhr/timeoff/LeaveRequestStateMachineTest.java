package com.caderly.caderlyhr.timeoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.caderly.caderlyhr.common.ConflictException;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive 4x4 cross-product over {@link LeaveRequestStatus} (CLAUDE.md §8: state machines get
 * exhaustive unit tests). Legal transitions per PRD §12.4/§21: PENDING -> {APPROVED, REJECTED,
 * CANCELLED}; APPROVED -> CANCELLED. Everything else — including every same-state "transition" —
 * is illegal: REJECTED/CANCELLED are terminal, and there is no re-decision path.
 */
class LeaveRequestStateMachineTest {

    private static final List<LeaveRequestStatus> LEGAL_TARGETS_FROM_PENDING =
            List.of(LeaveRequestStatus.APPROVED, LeaveRequestStatus.REJECTED, LeaveRequestStatus.CANCELLED);

    @ParameterizedTest
    @MethodSource("legalTransitions")
    void requireTransition_legalTransition_succeeds(LeaveRequestStatus from, LeaveRequestStatus to) {
        LeaveRequestStateMachine.requireTransition(from, to);
    }

    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void requireTransition_illegalTransition_throwsConflictException(
            LeaveRequestStatus from, LeaveRequestStatus to) {
        assertThatThrownBy(() -> LeaveRequestStateMachine.requireTransition(from, to))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).errorCode())
                .isEqualTo("LEAVE_REQUEST_ILLEGAL_TRANSITION");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> legalTransitions() {
        Stream.Builder<org.junit.jupiter.params.provider.Arguments> builder = Stream.builder();
        for (LeaveRequestStatus to : LEGAL_TARGETS_FROM_PENDING) {
            builder.add(arguments(LeaveRequestStatus.PENDING, to));
        }
        builder.add(arguments(LeaveRequestStatus.APPROVED, LeaveRequestStatus.CANCELLED));
        return builder.build();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> illegalTransitions() {
        Stream.Builder<org.junit.jupiter.params.provider.Arguments> builder = Stream.builder();
        for (LeaveRequestStatus from : LeaveRequestStatus.values()) {
            for (LeaveRequestStatus to : LeaveRequestStatus.values()) {
                if (isLegal(from, to)) {
                    continue;
                }
                builder.add(arguments(from, to));
            }
        }
        return builder.build();
    }

    private static boolean isLegal(LeaveRequestStatus from, LeaveRequestStatus to) {
        if (from == LeaveRequestStatus.PENDING) {
            return LEGAL_TARGETS_FROM_PENDING.contains(to);
        }
        if (from == LeaveRequestStatus.APPROVED) {
            return to == LeaveRequestStatus.CANCELLED;
        }
        return false;
    }

    @Test
    void allStatuses_haveExactlyTwelveIllegalTransitions() {
        // 4x4 = 16 total pairs; 4 legal (3 from PENDING + 1 from APPROVED) leaves 12 illegal.
        assertThat(EnumSet.allOf(LeaveRequestStatus.class)).hasSize(4);
        assertThat(illegalTransitions().count()).isEqualTo(12);
    }
}
