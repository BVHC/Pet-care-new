package com.petcare.module.workforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;

/**
 * {@link ShiftAssignmentMapper} (docs/adr/0138, workforce-v1 {@code ShiftAssignment}): mỗi trường của hợp đồng lấy đúng
 * nguồn — giá trị khác nhau ở mọi trường nên map nhầm nguồn là đỏ; đủ 13 trường theo thứ tự hợp đồng; {@code null} của
 * {@code reason} / {@code pendingRequestId} được giữ; không lọt {@code createdAt} / {@code updatedAt} /
 * {@code statusChanged*}; snapshot audit là bản sao giá trị với ngày và enum dạng chuỗi.
 */
class ShiftAssignmentMapperTest {

    private final ShiftAssignmentMapper mapper = Mappers.getMapper(ShiftAssignmentMapper.class);

    @Test
    void everyContractFieldComesFromItsSource() {
        ShiftAssignment assignment = scheduled(31L, 5L, 12L, 7L, LocalDate.of(2026, 11, 24));
        ShiftDefinition definition = definition(7L, 5L, "Ca sáng", LocalTime.of(8, 15), LocalTime.of(12, 45));

        ShiftAssignmentResponse response = mapper.toResponse(assignment, "Nguyễn Văn An", definition, 900L);

        assertThat(response).isEqualTo(new ShiftAssignmentResponse(31L, 5L, 12L, "Nguyễn Văn An", 7L, "Ca sáng",
                LocalTime.of(8, 15), LocalTime.of(12, 45), LocalDate.of(2026, 11, 24), ShiftAssignmentStatus.SCHEDULED,
                ShiftAssignmentSource.MANAGER, null, 900L));
    }

    @Test
    void theResponseHasExactlyTheThirteenContractFieldsInContractOrder() {
        assertThat(Arrays.stream(ShiftAssignmentResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("shiftAssignmentId", "branchId", "staffId", "staffName", "shiftDefinitionId",
                        "shiftName", "startTime", "endTime", "workDate", "status", "source", "reason",
                        "pendingRequestId");
    }

    @Test
    void nullablePendingRequestAndReasonStayNull() {
        ShiftAssignment assignment = scheduled(1L, 2L, 3L, 4L, LocalDate.of(2026, 12, 1));

        ShiftAssignmentResponse response = mapper.toResponse(assignment, "An", definition(4L, 2L, "B", LocalTime.of(7, 0),
                LocalTime.of(9, 0)), null);

        assertThat(response.pendingRequestId()).isNull();
        assertThat(response.reason()).isNull();
    }

    @Test
    void aClosedAssignmentCarriesItsStatusSourceAndReasonButNotWhoOrWhenItChanged() {
        ShiftAssignment assignment = ShiftAssignment.schedule(5L, 12L, 7L, LocalDate.of(2026, 11, 24),
                ShiftAssignmentSource.SELF);
        ReflectionTestUtils.setField(assignment, "id", 9L);
        assignment.cancel("Quản lý hủy", 77L, Instant.parse("2026-11-20T01:00:00Z"));

        ShiftAssignmentResponse response = mapper.toResponse(assignment, "An", definition(7L, 5L, "Ca", LocalTime.of(8, 0),
                LocalTime.of(12, 0)), null);

        assertThat(response.status()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
        assertThat(response.source()).isEqualTo(ShiftAssignmentSource.SELF);
        assertThat(response.reason()).isEqualTo("Quản lý hủy");
        assertThat(Arrays.stream(ShiftAssignmentResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("createdAt", "updatedAt", "statusChangedBy", "statusChangedAt");
    }

    @Test
    void anAssignmentMovedToAnotherShiftBySwapShowsTheNewShiftNameAndHoursAndStaysScheduledWithoutAReason() {
        ShiftAssignment assignment = scheduled(31L, 5L, 12L, 7L, LocalDate.of(2026, 11, 24));
        assignment.swapTo(8L);

        ShiftAssignmentResponse response = mapper.toResponse(assignment, "An", definition(8L, 5L, "Ca chiều",
                LocalTime.of(13, 0), LocalTime.of(17, 0)), null);

        assertThat(response.shiftDefinitionId()).isEqualTo(8L);
        assertThat(response.shiftName()).isEqualTo("Ca chiều");
        assertThat(response.startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(response.status()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(response.reason()).isNull();
    }

    @Test
    void snapshotUsesStringsForDateAndEnumsAndIsExactlyTheAuditSet() {
        ShiftAssignment assignment = scheduled(31L, 5L, 12L, 7L, LocalDate.of(2026, 11, 24));

        assertThat(mapper.toSnapshot(assignment)).isEqualTo(
                new ShiftAssignmentAuditSnapshot(12L, 7L, "2026-11-24", "SCHEDULED", "MANAGER", null));
        assertThat(Arrays.stream(ShiftAssignmentAuditSnapshot.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("staffId", "shiftDefinitionId", "workDate", "status", "source", "reason");
    }

    @Test
    void snapshotIsAValueCopyNotAViewOfTheLiveEntity() {
        ShiftAssignment assignment = scheduled(31L, 5L, 12L, 7L, LocalDate.of(2026, 11, 24));

        ShiftAssignmentAuditSnapshot before = mapper.toSnapshot(assignment);
        assignment.cancel("Hủy", 77L, Instant.parse("2026-11-20T01:00:00Z"));

        assertThat(before).isEqualTo(new ShiftAssignmentAuditSnapshot(12L, 7L, "2026-11-24", "SCHEDULED", "MANAGER", null));
        assertThat(mapper.toSnapshot(assignment)).isEqualTo(
                new ShiftAssignmentAuditSnapshot(12L, 7L, "2026-11-24", "CANCELLED", "MANAGER", "Hủy"));
    }

    private static ShiftAssignment scheduled(long id, long branchId, long staffId, long definitionId, LocalDate day) {
        ShiftAssignment assignment = ShiftAssignment.schedule(branchId, staffId, definitionId, day,
                ShiftAssignmentSource.MANAGER);
        ReflectionTestUtils.setField(assignment, "id", id);
        return assignment;
    }

    private static ShiftDefinition definition(long id, long branchId, String name, LocalTime start, LocalTime end) {
        ShiftDefinition definition = ShiftDefinition.create(branchId, name, start, end);
        ReflectionTestUtils.setField(definition, "id", id);
        return definition;
    }
}
