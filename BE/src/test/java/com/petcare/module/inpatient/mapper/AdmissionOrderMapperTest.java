package com.petcare.module.inpatient.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inpatient.dto.AdmissionOrderReasonRequest;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.dto.CompleteAdmissionOrderRequest;
import com.petcare.module.inpatient.dto.CreateAdmissionOrderRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;

/**
 * {@link AdmissionOrderMapper} (inpatient-v1 #5–#8, docs/adr/0149 mục 26): đủ trường của hợp đồng đúng thứ tự, mỗi trường
 * lấy đúng nguồn (giá trị khác nhau ở mọi cột nên lấy nhầm cột thì đỏ), trường {@code done_*} / {@code cancel_reason} chỉ có
 * ở đúng trạng thái, và không lộ người ra / người làm. Cây tên property so với yaml ở {@code OpenApiContractParityIT}.
 */
class AdmissionOrderMapperTest {

    private static final Instant SCHEDULED = Instant.parse("2026-11-25T01:00:00.123456Z");
    private static final Instant DONE_AT = Instant.parse("2026-11-25T01:05:00.654321Z");

    private final AdmissionOrderMapper mapper = Mappers.getMapper(AdmissionOrderMapper.class);

    @Test
    void theDtosHaveExactlyTheContractComponentsInContractOrder() {
        assertThat(names(AdmissionOrderResponse.class)).containsExactly("admissionOrderId", "admissionId", "orderType",
                "content", "scheduledAt", "status", "prescriptionItemId", "doneAt", "doneNote", "cancelReason");
        assertThat(names(CreateAdmissionOrderRequest.class)).containsExactly("orderType", "content", "scheduledAt",
                "prescriptionItemId");
        assertThat(names(CompleteAdmissionOrderRequest.class)).containsExactly("note");
        assertThat(names(AdmissionOrderReasonRequest.class)).containsExactly("reason");
    }

    @Test
    void anOrderedMedicationOrderMapsEveryColumnFromItsOwnSourceWithNoDoneFields() {
        AdmissionOrder order = order(AdmissionOrderType.MEDICATION, 7001L);

        AdmissionOrderResponse r = mapper.toResponse(order);

        assertThat(r.admissionOrderId()).isEqualTo(88L);
        assertThat(r.admissionId()).isEqualTo(51L);
        assertThat(r.orderType()).isEqualTo(AdmissionOrderType.MEDICATION);
        assertThat(r.content()).isEqualTo("Tiêm kháng sinh");
        assertThat(r.scheduledAt()).isEqualTo(SCHEDULED);
        assertThat(r.status()).isEqualTo(AdmissionOrderStatus.ORDERED);
        assertThat(r.prescriptionItemId()).isEqualTo(7001L);
        assertThat(r.doneAt()).isNull();
        assertThat(r.doneNote()).isNull();
        assertThat(r.cancelReason()).isNull();
    }

    @Test
    void aNonMedicationOrderHasNoPrescriptionItem() {
        assertThat(mapper.toResponse(order(AdmissionOrderType.FEEDING, null)).prescriptionItemId()).isNull();
    }

    @Test
    void aDoneOrderCarriesOnlyTheDoneFields() {
        AdmissionOrder order = order(AdmissionOrderType.FEEDING, null);
        order.complete(45L, DONE_AT, "Đã cho ăn");

        AdmissionOrderResponse r = mapper.toResponse(order);

        assertThat(r.status()).isEqualTo(AdmissionOrderStatus.DONE);
        assertThat(r.doneAt()).isEqualTo(DONE_AT);
        assertThat(r.doneNote()).isEqualTo("Đã cho ăn");
        assertThat(r.cancelReason()).isNull();
    }

    @Test
    void aCancelledOrderCarriesOnlyTheReason() {
        AdmissionOrder order = order(AdmissionOrderType.FEEDING, null);
        order.cancel("Thú đã ăn");

        AdmissionOrderResponse r = mapper.toResponse(order);

        assertThat(r.status()).isEqualTo(AdmissionOrderStatus.CANCELLED);
        assertThat(r.cancelReason()).isEqualTo("Thú đã ăn");
        assertThat(r.doneAt()).isNull();
        assertThat(r.doneNote()).isNull();
    }

    private static AdmissionOrder order(AdmissionOrderType type, Long itemId) {
        Admission admission = Admission.admit("NT-2611-00007", 9001L, 12L, 3L, null, 17L, 41L, 250_000L, 45L,
                Instant.parse("2026-11-22T02:00:00Z"));
        ReflectionTestUtils.setField(admission, "id", 51L);
        AdmissionOrder order = AdmissionOrder.order(admission, type,
                type == AdmissionOrderType.MEDICATION ? "Tiêm kháng sinh" : "Cho ăn", SCHEDULED, itemId, 45L);
        ReflectionTestUtils.setField(order, "id", 88L);
        return order;
    }

    private static String[] names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
    }
}
