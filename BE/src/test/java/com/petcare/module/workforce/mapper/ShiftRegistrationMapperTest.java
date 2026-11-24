package com.petcare.module.workforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;

/**
 * {@link ShiftRegistrationMapper} (workforce-v1 {@code ShiftRegistration}, docs/adr/0149): mỗi trường lấy đúng nguồn
 * (giá trị khác nhau ở mọi trường, nên hoán đổi {@code requiredShifts} / {@code shiftCount} là đỏ); {@code shiftCount}
 * luôn bằng số phần tử; danh sách rỗng là {@code []} chứ không {@code null}; đúng 5 trường theo thứ tự hợp đồng.
 */
class ShiftRegistrationMapperTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);
    private static final Instant DEADLINE = Instant.parse("2026-11-22T17:00:00Z");

    private final ShiftRegistrationMapper mapper = Mappers.getMapper(ShiftRegistrationMapper.class);

    @Test
    void everyContractFieldComesFromItsSource() {
        List<ShiftAssignmentResponse> list = List.of(row(31L, ShiftAssignmentSource.SELF),
                row(32L, ShiftAssignmentSource.MANAGER));

        ShiftRegistrationResponse response = mapper.toResponse(MONDAY, 13, DEADLINE, list);

        assertThat(response).isEqualTo(new ShiftRegistrationResponse(MONDAY, 13, 2, DEADLINE, list));
        assertThat(response.requiredShifts()).isNotEqualTo(response.shiftCount());
    }

    @Test
    void shiftCountAlwaysEqualsTheNumberOfAssignments() {
        for (int size : new int[] {0, 1, 3}) {
            List<ShiftAssignmentResponse> list = java.util.stream.LongStream.rangeClosed(1, size)
                    .mapToObj(id -> row(id, ShiftAssignmentSource.SELF)).toList();

            assertThat(mapper.toResponse(MONDAY, 10, DEADLINE, list).shiftCount()).as("size=%d", size).isEqualTo(size);
        }
    }

    @Test
    void anEmptyWeekKeepsAnEmptyListNotNull() {
        ShiftRegistrationResponse response = mapper.toResponse(MONDAY, 10, DEADLINE, List.of());

        assertThat(response.assignments()).isNotNull().isEmpty();
        assertThat(response.shiftCount()).isZero();
    }

    @Test
    void theResponseHasExactlyTheFiveContractFieldsInContractOrder() {
        assertThat(Arrays.stream(ShiftRegistrationResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("weekStart", "requiredShifts", "shiftCount", "editableUntil", "assignments");
    }

    private static ShiftAssignmentResponse row(long id, ShiftAssignmentSource source) {
        return new ShiftAssignmentResponse(id, 5L, 12L, "An", 7L, "Ca sáng", LocalTime.of(8, 15),
                LocalTime.of(12, 45), MONDAY.plusDays(id % 7), ShiftAssignmentStatus.SCHEDULED, source, null, null);
    }
}
