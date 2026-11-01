package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.dto.AssignableRole;
import com.petcare.module.identity.dto.AssignableStaffResponse;
import com.petcare.module.identity.exception.BranchSelectionRequiredException;
import com.petcare.module.identity.mapper.AssignableStaffMapper;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.security.BranchScope;

/**
 * Danh sách nhân viên có thể gán lượt (identity-v1 #38, UC45; BR-TN-05, 06; docs/adr/0089). Phạm vi chi nhánh bằng
 * {@link BranchScope} thật trên {@code SecurityContextHolder}: A05, A06 luôn chi nhánh mình (khác → 403), SUPER_MANAGER phải
 * gửi {@code branchId} (không → 400 và <b>không</b> gọi {@link StaffDirectoryApi}, vì API đó {@code requireNonNull}).
 */
class AssignableStaffServiceTest {

    private static final long OWN = 3L;
    private static final long OTHER = 4L;

    private final StaffDirectoryApi directory = mock(StaffDirectoryApi.class);
    private final AssignableStaffService service = new AssignableStaffService(new BranchScope(), directory,
            Mappers.getMapper(AssignableStaffMapper.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void branchScopedActorsWithoutBranchIdListTheirOwnBranch() {
        for (Role actor : List.of(Role.RECEPTIONIST, Role.BRANCH_MANAGER)) {
            actAs(actor, OWN);

            service.listAssignable(AssignableRole.VET, null);
        }
        verify(directory, times(2)).findAssignableStaff(OWN, Role.VET);
    }

    @Test
    void branchScopedActorsMayRepeatTheirOwnBranchId() {
        for (Role actor : List.of(Role.RECEPTIONIST, Role.BRANCH_MANAGER)) {
            actAs(actor, OWN);

            service.listAssignable(AssignableRole.CARETAKER, OWN);
        }
        verify(directory, times(2)).findAssignableStaff(OWN, Role.CARETAKER);
    }

    @Test
    void branchScopedActorsAskingForAnotherBranchAreDeniedWithoutReadingAnything() {
        for (Role actor : List.of(Role.RECEPTIONIST, Role.BRANCH_MANAGER)) {
            actAs(actor, OWN);

            assertThatThrownBy(() -> service.listAssignable(AssignableRole.VET, OTHER))
                    .as(actor.name()).isInstanceOf(AccessDeniedScopeException.class);
        }
        verifyNoInteractions(directory);
    }

    @Test
    void superManagerListsTheBranchItChose() {
        actAs(Role.SUPER_MANAGER, null);

        service.listAssignable(AssignableRole.CARETAKER, OTHER);

        verify(directory).findAssignableStaff(OTHER, Role.CARETAKER);
    }

    @Test
    void superManagerWithoutABranchGets400BeforeTheDirectoryIsCalled() {
        actAs(Role.SUPER_MANAGER, null);

        assertThatThrownBy(() -> service.listAssignable(AssignableRole.VET, null))
                .isInstanceOf(BranchSelectionRequiredException.class)
                .hasMessage("Vui lòng chọn chi nhánh")
                .satisfies(error -> assertThat(((BranchSelectionRequiredException) error).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(directory);
    }

    @Test
    void aCustomerIsDeniedByTheScopeEvenIfTheRoleCheckWereMissing() {
        actAs(Role.CUSTOMER, null);

        assertThatThrownBy(() -> service.listAssignable(AssignableRole.VET, OWN))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(directory);
    }

    @Test
    void mapsExactlyFourFieldsAndKeepsTheOrderOfTheDirectory() {
        actAs(Role.RECEPTIONIST, OWN);
        when(directory.findAssignableStaff(OWN, Role.VET)).thenReturn(List.of(
                new StaffSummary(30L, "Bình", Role.VET, OWN, true, true),
                new StaffSummary(10L, "An", Role.VET, OWN, true, false),
                new StaffSummary(20L, "An", Role.VET, OWN, true, false)));

        List<AssignableStaffResponse> result = service.listAssignable(AssignableRole.VET, null);

        assertThat(result).containsExactly(
                new AssignableStaffResponse(30L, "Bình", Role.VET, true),
                new AssignableStaffResponse(10L, "An", Role.VET, false),
                new AssignableStaffResponse(20L, "An", Role.VET, false));
        assertThat(Arrays.stream(AssignableStaffResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("accountId", "fullName", "role", "online");
    }

    @Test
    void anEmptyDirectoryGivesAnEmptyList() {
        actAs(Role.SUPER_MANAGER, null);
        when(directory.findAssignableStaff(any(), any())).thenReturn(List.of());

        assertThat(service.listAssignable(AssignableRole.VET, 999_999L)).isEmpty();
    }

    @Test
    void theRoleParameterHasExactlyTheTwoAssignableRoles() {
        assertThat(AssignableRole.values()).extracting(Enum::name).containsExactly("VET", "CARETAKER");
        for (AssignableRole role : AssignableRole.values()) {
            assertThat(role.role().name()).isEqualTo(role.name());
        }
    }

    private static void actAs(Role role, Long branchId) {
        AccountPrincipal principal = new AccountPrincipal(1L, "actor@petcare.test", 42L, role, branchId, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
