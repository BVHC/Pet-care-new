package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hai factory của {@link PrescriptionItem} (visit-v1 #16, docs/adr/0077): mỗi factory đặt cả ba cột mua ngoài cùng lúc
 * (C2 "không tách dòng" có CHECK ở DB; C3 "mua ngoài ⇔ có lý do" chỉ ứng dụng giữ — nợ D043) và chặn dữ liệu sai trước
 * khi chạm DB.
 */
class PrescriptionItemTest {

    private static final String DOSAGE = "Ngày 2 lần, mỗi lần 1 viên";

    @Test
    void prescribeLinksTheOrderLineAndIsNeverExternal() {
        PrescriptionItem item = PrescriptionItem.prescribe(9001L, 77L, 3, DOSAGE, 9101L);

        assertThat(item.getId()).isNull();
        assertThat(item.getMedicalRecordId()).isEqualTo(9001L);
        assertThat(item.getProductId()).isEqualTo(77L);
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getDosageInstructions()).isEqualTo(DOSAGE);
        assertThat(item.isExternalPurchase()).isFalse();
        assertThat(item.getOrderLineId()).isEqualTo(9101L);
        assertThat(item.getExternalReason()).isNull();
    }

    @Test
    void externalAtPrescribeHasNoOrderLineAndTheReasonOfThePrescribeTime() {
        PrescriptionItem item = PrescriptionItem.externalAtPrescribe(9001L, 77L, 5, DOSAGE);

        assertThat(item.getQuantity()).as("mua ngoài giữ nguyên số lượng (BR-KB-03)").isEqualTo(5);
        assertThat(item.isExternalPurchase()).isTrue();
        assertThat(item.getOrderLineId()).isNull();
        assertThat(item.getExternalReason()).isEqualTo(ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE);
    }

    @Test
    void prescribeWithoutAnOrderLineIsRejected() {
        assertThatThrownBy(() -> PrescriptionItem.prescribe(9001L, 77L, 3, DOSAGE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aNonPositiveQuantityIsRejectedByBothFactories(int quantity) {
        assertThatThrownBy(() -> PrescriptionItem.prescribe(9001L, 77L, quantity, DOSAGE, 9101L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrescriptionItem.externalAtPrescribe(9001L, 77L, quantity, DOSAGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingIdsAreRejected() {
        assertThatThrownBy(() -> PrescriptionItem.prescribe(null, 77L, 1, DOSAGE, 9101L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrescriptionItem.externalAtPrescribe(9001L, null, 1, DOSAGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dosageMustBePresentAndAtMost500Characters() {
        assertThatThrownBy(() -> PrescriptionItem.prescribe(9001L, 77L, 1, null, 9101L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrescriptionItem.externalAtPrescribe(9001L, 77L, 1, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PrescriptionItem.prescribe(9001L, 77L, 1, "x".repeat(501), 9101L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(PrescriptionItem.prescribe(9001L, 77L, 1, "x".repeat(500), 9101L).getDosageInstructions()).hasSize(500);
    }
}
