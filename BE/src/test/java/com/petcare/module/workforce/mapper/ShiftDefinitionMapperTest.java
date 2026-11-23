package com.petcare.module.workforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.workforce.dto.ShiftDefinitionAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftDefinitionResponse;
import com.petcare.module.workforce.entity.ShiftDefinition;

/**
 * {@link ShiftDefinitionMapper} (docs/adr/0149, workforce-v1 {@code ShiftDefinition}): mỗi trường của hợp đồng lấy đúng
 * cột — giá trị khác nhau ở mọi trường nên map nhầm nguồn là đỏ; không lọt {@code createdAt} / {@code updatedAt};
 * snapshot audit có giờ dạng chuỗi {@code HH:mm}.
 */
class ShiftDefinitionMapperTest {

    private final ShiftDefinitionMapper mapper = Mappers.getMapper(ShiftDefinitionMapper.class);

    @Test
    void everyContractFieldComesFromItsColumn() {
        ShiftDefinition definition = definition(11L, 22L, "Ca sáng", LocalTime.of(8, 15), LocalTime.of(12, 45));

        assertThat(mapper.toResponse(definition)).isEqualTo(
                new ShiftDefinitionResponse(11L, 22L, "Ca sáng", LocalTime.of(8, 15), LocalTime.of(12, 45), true));
        assertThat(Arrays.stream(ShiftDefinitionResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("shiftDefinitionId", "branchId", "name", "startTime", "endTime", "active");
    }

    @Test
    void inactiveFlagIsCarried() {
        ShiftDefinition definition = definition(1L, 2L, "Ca chiều", LocalTime.of(14, 0), LocalTime.of(19, 0));
        definition.deactivate();

        assertThat(mapper.toResponse(definition).active()).isFalse();
    }

    @Test
    void listKeepsOrderAndEmptyStaysEmpty() {
        ShiftDefinition first = definition(1L, 5L, "A", LocalTime.of(7, 0), LocalTime.of(11, 0));
        ShiftDefinition second = definition(2L, 5L, "B", LocalTime.of(13, 0), LocalTime.of(17, 0));

        assertThat(mapper.toResponses(List.of(first, second))).extracting(ShiftDefinitionResponse::shiftDefinitionId)
                .containsExactly(1L, 2L);
        assertThat(mapper.toResponses(List.of())).isEmpty();
    }

    @Test
    void snapshotTimesAreHourMinuteStringsAndFieldsAreExactlyTheAuditSet() {
        ShiftDefinition definition = definition(11L, 22L, "Ca sáng", LocalTime.of(8, 15), LocalTime.of(12, 45));

        assertThat(mapper.toSnapshot(definition))
                .isEqualTo(new ShiftDefinitionAuditSnapshot("Ca sáng", "08:15", "12:45", true));
        assertThat(Arrays.stream(ShiftDefinitionAuditSnapshot.class.getRecordComponents())
                .map(RecordComponent::getName)).containsExactly("name", "startTime", "endTime", "active");
    }

    @Test
    void snapshotIsAValueCopyNotAViewOfTheLiveEntity() {
        ShiftDefinition definition = definition(11L, 22L, "Ca sáng", LocalTime.of(8, 15), LocalTime.of(12, 45));

        ShiftDefinitionAuditSnapshot before = mapper.toSnapshot(definition);
        definition.rename("Ca mới");
        definition.deactivate();

        assertThat(before).isEqualTo(new ShiftDefinitionAuditSnapshot("Ca sáng", "08:15", "12:45", true));
        assertThat(mapper.toSnapshot(definition))
                .isEqualTo(new ShiftDefinitionAuditSnapshot("Ca mới", "08:15", "12:45", false));
    }

    private static ShiftDefinition definition(long id, long branchId, String name, LocalTime start, LocalTime end) {
        ShiftDefinition definition = ShiftDefinition.create(branchId, name, start, end);
        ReflectionTestUtils.setField(definition, "id", id);
        return definition;
    }
}
