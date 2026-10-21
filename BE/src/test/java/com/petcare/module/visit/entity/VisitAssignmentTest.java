package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link VisitAssignment#of} (docs/adr/0061): mỗi tham số vào đúng cột — giá trị khác nhau để bắt đảo tham số. */
class VisitAssignmentTest {

    @Test
    void ofKeepsEachColumn() {
        VisitAssignment assignment = VisitAssignment.of(44L, 11L, 22L, 33L, VisitStatus.WAITING, "lý do", true);

        assertThat(assignment.getVisitId()).isEqualTo(44L);
        assertThat(assignment.getFromAccountId()).isEqualTo(11L);
        assertThat(assignment.getToAccountId()).isEqualTo(22L);
        assertThat(assignment.getAssignedBy()).isEqualTo(33L);
        assertThat(assignment.getVisitStatusAtAssign()).isEqualTo(VisitStatus.WAITING);
        assertThat(assignment.getReason()).isEqualTo("lý do");
        assertThat(assignment.isAssigneeWasOffline()).isTrue();
        assertThat(assignment.getId()).as("id do DB sinh").isNull();
    }

    @Test
    void firstAssignmentHasNoPreviousAssigneeNorReason() {
        VisitAssignment assignment = VisitAssignment.of(44L, null, 22L, 33L, VisitStatus.WAITING, null, false);

        assertThat(assignment.getFromAccountId()).isNull();
        assertThat(assignment.getReason()).isNull();
        assertThat(assignment.isAssigneeWasOffline()).isFalse();
    }
}
