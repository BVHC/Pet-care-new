package com.petcare.module.inpatient.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inpatient.dto.CreateObservationRequest;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.Appetite;

/**
 * {@link AdmissionObservationMapper} (inpatient-v1 #9–#10, docs/adr/0149 mục 31, 36): đủ trường của hợp đồng đúng thứ
 * tự, mỗi trường lấy đúng nguồn (giá trị khác nhau ở mọi cột nên lấy nhầm cột thì đỏ), {@code createdAt} và tên người ghi
 * lấy từ tham số chứ không từ entity, trường trống giữ {@code null}. Cây tên property so với yaml ở
 * {@code OpenApiContractParityIT}.
 */
class AdmissionObservationMapperTest {

    private static final Instant ENTITY_CLOCK = Instant.parse("2026-11-25T01:05:00.123456789Z");
    private static final Instant STORED = Instant.parse("2026-11-25T01:05:00.123456Z");

    private final AdmissionObservationMapper mapper = Mappers.getMapper(AdmissionObservationMapper.class);

    @Test
    void theDtosHaveExactlyTheContractComponentsInContractOrder() {
        assertThat(names(ObservationResponse.class)).containsExactly("observationId", "admissionId", "recordedByName",
                "temperatureC", "heartRate", "respiratoryRate", "weightKg", "appetite", "note", "abnormal", "createdAt");
        assertThat(names(CreateObservationRequest.class)).containsExactly("temperatureC", "heartRate",
                "respiratoryRate", "weightKg", "appetite", "note", "abnormal");
    }

    @Test
    void everyFieldComesFromItsOwnSourceAndShortBecomesInteger() {
        AdmissionObservation o = observation(new BigDecimal("38.6"), 120, 30, new BigDecimal("5.55"), Appetite.REDUCED,
                "Ăn ít", true);

        ObservationResponse r = mapper.toResponse(o, "Trần Văn Minh", STORED);

        assertThat(r.observationId()).isEqualTo(301L);
        assertThat(r.admissionId()).isEqualTo(51L);
        assertThat(r.recordedByName()).isEqualTo("Trần Văn Minh");
        assertThat(r.temperatureC()).isEqualByComparingTo("38.6");
        assertThat(r.heartRate()).isEqualTo(120);
        assertThat(r.respiratoryRate()).isEqualTo(30);
        assertThat(r.weightKg()).isEqualByComparingTo("5.55");
        assertThat(r.appetite()).isEqualTo(Appetite.REDUCED);
        assertThat(r.note()).isEqualTo("Ăn ít");
        assertThat(r.abnormal()).isTrue();
    }

    @Test
    void createdAtComesFromTheParameterNotFromTheNanosecondEntityTimestamp() {
        AdmissionObservation o = observation(null, null, null, null, null, "Ổn", false);
        ReflectionTestUtils.setField(o, "createdAt", ENTITY_CLOCK);

        assertThat(mapper.toResponse(o, "Minh", STORED).createdAt()).isEqualTo(STORED);
    }

    @Test
    void emptyMeasurementsStayNull() {
        ObservationResponse r = mapper.toResponse(observation(null, null, null, null, null, "Ổn", false), "Minh", STORED);

        assertThat(r.temperatureC()).isNull();
        assertThat(r.heartRate()).isNull();
        assertThat(r.respiratoryRate()).isNull();
        assertThat(r.weightKg()).isNull();
        assertThat(r.appetite()).isNull();
        assertThat(r.abnormal()).isFalse();
    }

    private static AdmissionObservation observation(BigDecimal temperature, Integer heart, Integer respiratory,
            BigDecimal weight, Appetite appetite, String note, boolean abnormal) {
        Admission admission = Admission.admit("NT-2611-00051", 9001L, 12L, 3L, null, 17L, 41L, 200_000L, 7L,
                Instant.parse("2026-11-22T02:00:00Z"));
        ReflectionTestUtils.setField(admission, "id", 51L);
        AdmissionObservation o = AdmissionObservation.record(admission, 45L, temperature, heart, respiratory, weight,
                appetite, note, abnormal);
        ReflectionTestUtils.setField(o, "id", 301L);
        return o;
    }

    private static java.util.List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
