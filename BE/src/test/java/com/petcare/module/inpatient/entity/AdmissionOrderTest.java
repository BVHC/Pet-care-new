package com.petcare.module.inpatient.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Bất biến tự thân của {@link AdmissionOrder} (docs/adr/0149 §1b O1–O10). */
class AdmissionOrderTest {

    private static final Instant AT = Instant.parse("2043-03-14T02:00:00Z");

    /** Ca đã lưu (có id) ở trạng thái {@code ADMITTED}. */
    private static Admission admitted() {
        Admission a = Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 200_000L, 7L, AT);
        ReflectionTestUtils.setField(a, "id", 10L);
        return a;
    }

    private static AdmissionOrder feeding() {
        return AdmissionOrder.order(admitted(), AdmissionOrderType.FEEDING, "  Cho ăn pate  ", AT, null, 7L);
    }

    @Test
    void orderStartsOrderedAndTrimsContent() {
        AdmissionOrder o = feeding();

        assertThat(o.getStatus()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertThat(o.getAdmissionId()).isEqualTo(10L);
        assertThat(o.getOrderType()).isEqualTo(AdmissionOrderType.FEEDING);
        assertThat(o.getContent()).isEqualTo("Cho ăn pate");
        assertThat(o.getScheduledAt()).isEqualTo(AT);
        assertThat(o.getPrescriptionItemId()).isNull();
        assertThat(o.getCreatedBy()).isEqualTo(7L);
        assertThat(o.getDoneBy()).isNull();
        assertThat(o.getCancelReason()).isNull();
    }

    @Test
    void medicationNeedsAPrescriptionItemButOtherTypesDoNot() {
        assertThatThrownBy(() -> AdmissionOrder.order(admitted(), AdmissionOrderType.MEDICATION, "Kháng sinh", AT, null,
                7L)).isInstanceOf(IllegalArgumentException.class);

        AdmissionOrder medication = AdmissionOrder.order(admitted(), AdmissionOrderType.MEDICATION, "Kháng sinh", AT,
                33L, 7L);
        assertThat(medication.getPrescriptionItemId()).isEqualTo(33L);
        // loại khác được phép kèm dòng đơn thuốc (BR-LT-15: tùy ý)
        assertThat(AdmissionOrder.order(admitted(), AdmissionOrderType.OTHER, "Ghi chú", AT, 33L, 7L)
                .getPrescriptionItemId()).isEqualTo(33L);
    }

    @Test
    void orderIsRejectedForADischargedAdmission() {
        Admission a = admitted();
        a.discharge(DischargeOutcome.HOME, "Xong", 7L, AT, 55L, 0);

        assertThatThrownBy(() -> AdmissionOrder.order(a, AdmissionOrderType.FEEDING, "Cho ăn", AT, null, 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void orderRejectsBlankOrOverlongContentAndMissingColumns() {
        Admission a = admitted();

        assertThatThrownBy(() -> AdmissionOrder.order(a, AdmissionOrderType.FEEDING, " 　 ", AT, null, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdmissionOrder.order(a, AdmissionOrderType.FEEDING, "x".repeat(501), AT, null, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AdmissionOrder.order(a, AdmissionOrderType.FEEDING, "x".repeat(500), AT, null, 7L).getContent())
                .hasSize(500);
        assertThatThrownBy(() -> AdmissionOrder.order(a, null, "Cho ăn", AT, null, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AdmissionOrder.order(a, AdmissionOrderType.FEEDING, "Cho ăn", null, null, 7L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AdmissionOrder.order(a, AdmissionOrderType.FEEDING, "Cho ăn", AT, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void orderNeedsAnAdmissionThatHasBeenSaved() {
        Admission unsaved = Admission.admit("NT-4303-00001", 1L, 2L, 3L, null, 9L, 5L, 1L, 7L, AT);

        assertThatThrownBy(() -> AdmissionOrder.order(unsaved, AdmissionOrderType.FEEDING, "Cho ăn", AT, null, 7L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void completeFillsTheDoneColumnsAndTrimsTheNote() {
        AdmissionOrder o = feeding();
        Instant done = AT.plusSeconds(600);

        o.complete(8L, done, "  Ăn hết  ");

        assertThat(o.getStatus()).isEqualTo(AdmissionOrderStatus.DONE);
        assertThat(o.getDoneBy()).isEqualTo(8L);
        assertThat(o.getDoneAt()).isEqualTo(done);
        assertThat(o.getDoneNote()).isEqualTo("Ăn hết");
        assertThat(o.getCancelReason()).isNull();
    }

    @Test
    void completeRejectsMissingOrOverlongNoteAndWritesNothing() {
        AdmissionOrder o = feeding();

        assertThatThrownBy(() -> o.complete(8L, AT, "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> o.complete(8L, AT, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> o.complete(8L, AT, "x".repeat(301))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> o.complete(null, AT, "Xong")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> o.complete(8L, null, "Xong")).isInstanceOf(NullPointerException.class);

        assertThat(o.getStatus()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertThat(o.getDoneBy()).isNull();
        assertThat(o.getDoneAt()).isNull();
        assertThat(o.getDoneNote()).isNull();
    }

    @Test
    void cancelFillsTheReasonAndRejectsAMissingOne() {
        AdmissionOrder o = feeding();

        assertThatThrownBy(() -> o.cancel(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> o.cancel("x".repeat(301))).isInstanceOf(IllegalArgumentException.class);
        assertThat(o.getStatus()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertThat(o.getCancelReason()).isNull();

        o.cancel("  Thú không ăn  ");

        assertThat(o.getStatus()).isEqualTo(AdmissionOrderStatus.CANCELLED);
        assertThat(o.getCancelReason()).isEqualTo("Thú không ăn");
        assertThat(o.getDoneBy()).isNull();
    }

    @Test
    void finalStatesAcceptNoFurtherChange() {
        AdmissionOrder done = feeding();
        done.complete(8L, AT, "Xong");
        AdmissionOrder cancelled = feeding();
        cancelled.cancel("Hủy");

        assertThatThrownBy(() -> done.complete(8L, AT, "Lần hai")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> done.cancel("Hủy")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancelled.complete(8L, AT, "Xong")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancelled.cancel("Hủy lần hai")).isInstanceOf(IllegalStateException.class);

        assertThat(done.getDoneNote()).isEqualTo("Xong");
        assertThat(done.getCancelReason()).isNull();
        assertThat(cancelled.getCancelReason()).isEqualTo("Hủy");
        assertThat(cancelled.getDoneNote()).isNull();
    }

    @Test
    void onlyMedicationRequiresAVet() {
        for (AdmissionOrderType type : AdmissionOrderType.values()) {
            AdmissionOrder o = AdmissionOrder.order(admitted(), type, "Nội dung", AT,
                    type == AdmissionOrderType.MEDICATION ? 33L : null, 7L);
            assertThat(o.requiresVet()).as(type.name()).isEqualTo(type == AdmissionOrderType.MEDICATION);
        }
    }
}
