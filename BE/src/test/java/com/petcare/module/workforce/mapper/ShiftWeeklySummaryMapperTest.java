package com.petcare.module.workforce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.dto.ShiftWeeklySummaryResponse;

/**
 * {@link ShiftWeeklySummaryMapper} (workforce-v1 {@code ShiftWeeklySummary}, #16, docs/adr/0149): mỗi trường lấy đúng
 * nguồn (giá trị khác nhau ở mọi trường, nên hoán đổi {@code shiftCount} / {@code requiredShifts} là đỏ);
 * {@code meetsRequirement} là {@code shiftCount >= requiredShifts} (biên 9 / 10 / 11); đúng 6 trường theo thứ tự hợp đồng.
 */
class ShiftWeeklySummaryMapperTest {

    private final ShiftWeeklySummaryMapper mapper = Mappers.getMapper(ShiftWeeklySummaryMapper.class);

    private static StaffSummary staff() {
        return new StaffSummary(31L, "Nguyễn Văn An", Role.VET, 5L, true, true);
    }

    @Test
    void everyContractFieldComesFromItsSource() {
        ShiftWeeklySummaryResponse response = mapper.toResponse(staff(), 7, 10);

        assertThat(response).isEqualTo(new ShiftWeeklySummaryResponse(31L, "Nguyễn Văn An", Role.VET, 7, 10, false));
        assertThat(response.shiftCount()).isNotEqualTo(response.requiredShifts());
    }

    @Test
    void theRoleIsTheStaffMembersOwnRoleForEachSchedulableRole() {
        for (Role role : new Role[] {Role.RECEPTIONIST, Role.VET, Role.CARETAKER}) {
            StaffSummary member = new StaffSummary(1L, "A", role, 5L, true, false);

            assertThat(mapper.toResponse(member, 0, 10).role()).isEqualTo(role);
        }
    }

    @Test
    void theRequirementIsMetFromExactlyTheQuotaUpwardAndNeverBelow() {
        assertThat(mapper.toResponse(staff(), 9, 10).meetsRequirement()).isFalse();
        assertThat(mapper.toResponse(staff(), 10, 10).meetsRequirement()).isTrue();
        assertThat(mapper.toResponse(staff(), 11, 10).meetsRequirement()).isTrue();
    }

    @Test
    void aStaffMemberWithoutShiftsDoesNotMeetANormalQuotaButEveryoneMeetsAQuotaOfZero() {
        assertThat(mapper.toResponse(staff(), 0, 10).meetsRequirement()).isFalse();
        assertThat(mapper.toResponse(staff(), 0, 0).meetsRequirement()).isTrue();
    }

    @Test
    void theOnlineFlagTheBranchAndTheActiveFlagOfTheSourceAreNotPartOfTheResponse() {
        assertThat(Arrays.stream(ShiftWeeklySummaryResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("staffId", "staffName", "role", "shiftCount", "requiredShifts", "meetsRequirement");
    }
}
