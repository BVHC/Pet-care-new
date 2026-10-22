package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link MedicalRecord#of} (docs/adr/0064 §1): bệnh án rỗng, PK = id lượt. */
class MedicalRecordTest {

    @Test
    void ofSetsOnlyTheVisitId() {
        MedicalRecord record = MedicalRecord.of(9001L);

        assertThat(record.getVisitId()).isEqualTo(9001L);
        assertThat(record.getExamination()).isNull();
        assertThat(record.getDiagnosis()).isNull();
        assertThat(record.getTreatmentPlan()).isNull();
        assertThat(record.getInternalNote()).isNull();
        assertThat(record.getFollowUpDate()).isNull();
        assertThat(record.getFollowUpRemindedAt()).isNull();
        assertThat(record.getFollowUpCancelledAt()).isNull();
        assertThat(record.getLastEditedBy()).isNull();
        assertThat(record.getLockedAt()).as("khóa ở Visit#5").isNull();
    }

    @Test
    void rewriteReplacesTheFiveContentFieldsAndTheEditorOnly() {
        MedicalRecord record = MedicalRecord.of(9001L);
        Instant reminded = Instant.parse("2026-10-20T00:00:00Z");
        Instant locked = Instant.parse("2026-10-21T00:00:00Z");
        ReflectionTestUtils.setField(record, "followUpRemindedAt", reminded);
        ReflectionTestUtils.setField(record, "followUpCancelledAt", reminded);
        ReflectionTestUtils.setField(record, "lockedAt", locked);

        record.rewrite("khám", "dx", "kế hoạch", "nội bộ", LocalDate.of(2026, 11, 5), 45L);

        assertThat(record.getVisitId()).isEqualTo(9001L);
        assertThat(record.getExamination()).isEqualTo("khám");
        assertThat(record.getDiagnosis()).isEqualTo("dx");
        assertThat(record.getTreatmentPlan()).isEqualTo("kế hoạch");
        assertThat(record.getInternalNote()).isEqualTo("nội bộ");
        assertThat(record.getFollowUpDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(record.getLastEditedBy()).isEqualTo(45L);
        assertThat(record.getFollowUpRemindedAt()).isEqualTo(reminded);
        assertThat(record.getFollowUpCancelledAt()).isEqualTo(reminded);
        assertThat(record.getLockedAt()).isEqualTo(locked);
    }

    @Test
    void rewriteWithNullsClearsTheFields() {
        MedicalRecord record = MedicalRecord.of(9001L);
        record.rewrite("khám", "dx", "kế hoạch", "nội bộ", LocalDate.of(2026, 11, 5), 45L);

        record.rewrite(null, null, null, null, null, 46L);

        assertThat(record.getExamination()).isNull();
        assertThat(record.getDiagnosis()).isNull();
        assertThat(record.getTreatmentPlan()).isNull();
        assertThat(record.getInternalNote()).isNull();
        assertThat(record.getFollowUpDate()).isNull();
        assertThat(record.getLastEditedBy()).isEqualTo(46L);
    }

    @Test
    void rewriteRequiresAnEditor() {
        assertThatThrownBy(() -> MedicalRecord.of(9001L).rewrite("a", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameContentComparesEachOfTheFiveFields() {
        MedicalRecord record = MedicalRecord.of(9001L);
        LocalDate day = LocalDate.of(2026, 11, 5);
        record.rewrite("a", "b", "c", "d", day, 45L);

        assertThat(record.sameContent("a", "b", "c", "d", day)).isTrue();
        assertThat(record.sameContent("x", "b", "c", "d", day)).isFalse();
        assertThat(record.sameContent("a", "x", "c", "d", day)).isFalse();
        assertThat(record.sameContent("a", "b", "x", "d", day)).isFalse();
        assertThat(record.sameContent("a", "b", "c", "x", day)).isFalse();
        assertThat(record.sameContent("a", "b", "c", "d", day.plusDays(1))).isFalse();
        assertThat(record.sameContent("a", "b", "c", "d", null)).isFalse();
        assertThat(MedicalRecord.of(1L).sameContent(null, null, null, null, null)).isTrue();
    }

    @Test
    void visitIdIsRequired() {
        assertThatThrownBy(() -> MedicalRecord.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
