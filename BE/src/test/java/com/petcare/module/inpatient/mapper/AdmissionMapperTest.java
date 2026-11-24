package com.petcare.module.inpatient.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmissionSummaryResponse;
import com.petcare.module.inpatient.dto.AdmitRequest;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.entity.DischargeOutcome;

/**
 * {@link AdmissionMapper} (inpatient-v1 #1–#3, docs/adr/0149 mục 17): đủ trường của hợp đồng đúng thứ tự, mỗi trường lấy
 * đúng nguồn (giá trị khác nhau ở mọi cột nên lấy nhầm cột thì đỏ), hai đường nhập có đúng một nguồn, trường xuất viện
 * null khi {@code ADMITTED}, số tính toán truyền nguyên. Cây tên property so với yaml ở {@code OpenApiContractParityIT}.
 */
class AdmissionMapperTest {

    private static final Instant ADMITTED_AT = Instant.parse("2026-11-22T02:00:00.123456Z");
    private static final Instant DISCHARGED_AT = Instant.parse("2026-11-24T05:00:00Z");

    private final AdmissionMapper mapper = Mappers.getMapper(AdmissionMapper.class);

    @Test
    void theDtosHaveExactlyTheContractComponentsInContractOrder() {
        assertThat(names(AdmissionResponse.class)).containsExactly("admissionId", "code", "visitId", "petId", "petName",
                "branchId", "boardingBookingId", "kennelId", "serviceId", "serviceName", "unitPrice", "status",
                "admittedAt", "billedDays", "estimatedAmount", "dischargeOutcome", "dischargeSummary", "dischargedAt",
                "orderLineId");
        assertThat(names(AdmissionSummaryResponse.class)).containsExactly("admissionId", "code", "petName", "status",
                "admittedAt", "kennelCode", "billedDays", "estimatedAmount");
        assertThat(names(AdmitRequest.class)).containsExactly("visitId", "boardingBookingId", "serviceId", "kennelId");
    }

    @Test
    void anAdmissionFromAVisitMapsEveryColumnFromItsOwnSource() {
        Admission admission = admission(null, 17L);

        AdmissionResponse r = mapper.toResponse(admission, "Mochi", "Nội trú điều trị", 3, 750_000L);

        assertThat(r.admissionId()).isEqualTo(51L);
        assertThat(r.code()).isEqualTo("NT-2611-00007");
        assertThat(r.visitId()).isEqualTo(9001L);
        assertThat(r.petId()).isEqualTo(12L);
        assertThat(r.petName()).isEqualTo("Mochi");
        assertThat(r.branchId()).isEqualTo(3L);
        assertThat(r.boardingBookingId()).isNull();
        assertThat(r.kennelId()).isEqualTo(17L);
        assertThat(r.serviceId()).isEqualTo(41L);
        assertThat(r.serviceName()).isEqualTo("Nội trú điều trị");
        assertThat(r.unitPrice()).isEqualTo(250_000L);
        assertThat(r.status()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(r.admittedAt()).isEqualTo(ADMITTED_AT);
        assertThat(r.billedDays()).isEqualTo(3);
        assertThat(r.estimatedAmount()).isEqualTo(750_000L);
        assertThat(r.dischargeOutcome()).isNull();
        assertThat(r.dischargeSummary()).isNull();
        assertThat(r.dischargedAt()).isNull();
        assertThat(r.orderLineId()).isNull();
    }

    @Test
    void anAdmissionFromABoardingStayHasTheBookingAndNoKennel() {
        AdmissionResponse r = mapper.toResponse(admission(301L, null), "Bơ", "Nội trú", 1, 250_000L);

        assertThat(r.boardingBookingId()).isEqualTo(301L);
        assertThat(r.kennelId()).isNull();
    }

    @Test
    void aDischargedAdmissionMapsTheDischargeColumns() {
        Admission admission = admission(null, 17L);
        admission.discharge(DischargeOutcome.HOME, "Hết sốt", 46L, DISCHARGED_AT, 7301L, 0);

        AdmissionResponse r = mapper.toResponse(admission, "Mochi", "Nội trú", 3, 750_000L);

        assertThat(r.status()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(r.dischargeOutcome()).isEqualTo(DischargeOutcome.HOME);
        assertThat(r.dischargeSummary()).isEqualTo("Hết sốt");
        assertThat(r.dischargedAt()).isEqualTo(DISCHARGED_AT);
        assertThat(r.orderLineId()).isEqualTo(7301L);
    }

    @Test
    void theSummaryMapsEachFieldFromItsOwnSource() {
        AdmissionSummaryResponse s = mapper.toSummary(admission(301L, null), "Bơ", "C-03", 4, 1_000_000L);

        assertThat(s.admissionId()).isEqualTo(51L);
        assertThat(s.code()).isEqualTo("NT-2611-00007");
        assertThat(s.petName()).isEqualTo("Bơ");
        assertThat(s.status()).isEqualTo(AdmissionStatus.ADMITTED);
        assertThat(s.admittedAt()).isEqualTo(ADMITTED_AT);
        assertThat(s.kennelCode()).isEqualTo("C-03");
        assertThat(s.billedDays()).isEqualTo(4);
        assertThat(s.estimatedAmount()).isEqualTo(1_000_000L);
    }

    private static Admission admission(Long bookingId, Long kennelId) {
        Admission admission = Admission.admit("NT-2611-00007", 9001L, 12L, 3L, bookingId, kennelId, 41L, 250_000L,
                45L, ADMITTED_AT);
        ReflectionTestUtils.setField(admission, "id", 51L);
        return admission;
    }

    private static String[] names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
    }
}
