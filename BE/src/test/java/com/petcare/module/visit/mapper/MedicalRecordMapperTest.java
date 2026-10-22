package com.petcare.module.visit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.visit.dto.AddendumResponse;
import com.petcare.module.visit.dto.MedicalRecordResponse;
import com.petcare.module.visit.dto.WeightRecordRefResponse;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;

/**
 * {@link MedicalRecordMapper} (docs/adr/0064): mỗi trường một giá trị riêng nên map nhầm nguồn là đỏ; {@code locked}
 * lấy từ tham số, không từ {@code locked_at}; danh sách không bao giờ {@code null}.
 */
class MedicalRecordMapperTest {

    private final MedicalRecordMapper mapper = Mappers.getMapper(MedicalRecordMapper.class);

    @Test
    void responseMapsEveryFieldFromItsOwnSource() {
        MedicalRecord record = MedicalRecord.of(9001L);
        ReflectionTestUtils.setField(record, "examination", "khám");
        ReflectionTestUtils.setField(record, "diagnosis", "chẩn đoán");
        ReflectionTestUtils.setField(record, "treatmentPlan", "điều trị");
        ReflectionTestUtils.setField(record, "internalNote", "nội bộ");
        ReflectionTestUtils.setField(record, "followUpDate", LocalDate.of(2026, 11, 5));
        ReflectionTestUtils.setField(record, "lastEditedBy", 45L);
        ReflectionTestUtils.setField(record, "lockedAt", Instant.parse("2026-10-22T03:00:00Z"));
        AddendumResponse addendum = new AddendumResponse(301L, "bổ sung", false, 46L, "BS. Lan",
                Instant.parse("2026-10-22T04:00:00Z"));

        MedicalRecordResponse response = mapper.toResponse(record, false, List.of(addendum));

        assertThat(response).isEqualTo(new MedicalRecordResponse(9001L, "khám", "chẩn đoán", "điều trị", "nội bộ",
                LocalDate.of(2026, 11, 5), false, 45L, List.of(), List.of(), List.of(addendum)));
    }

    @Test
    void lockedComesFromTheCallerNotFromLockedAt() {
        assertThat(mapper.toResponse(MedicalRecord.of(1L), true, List.of()).locked()).isTrue();
    }

    @Test
    void emptyResponseKeepsTheVisitAndHasEmptyLists() {
        assertThat(mapper.toEmptyResponse(9001L, true)).isEqualTo(new MedicalRecordResponse(9001L, null, null, null,
                null, null, true, null, List.of(), List.of(), List.of()));
    }

    @Test
    void addendumMapsEveryFieldAndTakesTheNameFromTheCaller() {
        MedicalRecordAddendum addendum = MedicalRecordAddendum.of(9001L, "bổ sung", true, 46L);
        ReflectionTestUtils.setField(addendum, "id", 301L);
        ReflectionTestUtils.setField(addendum, "createdAt", Instant.parse("2026-10-22T04:00:00Z"));

        assertThat(mapper.toAddendum(addendum, "BS. Lan")).isEqualTo(new AddendumResponse(301L, "bổ sung", true, 46L,
                "BS. Lan", Instant.parse("2026-10-22T04:00:00Z")));
    }

    @Test
    void weightRefMapsEveryField() {
        Instant at = Instant.parse("2026-10-22T03:00:00.123456Z");

        assertThat(mapper.toWeightRef(812L, new BigDecimal("12.35"), at))
                .isEqualTo(new WeightRecordRefResponse(812L, new BigDecimal("12.35"), at));
    }
}
