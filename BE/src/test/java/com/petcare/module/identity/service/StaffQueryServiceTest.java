package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Phạm vi xem nhân viên (identity-v1 #15, #17; docs/adr/0029 mục 1, docs/adr/0030): ADMIN → SUPER_MANAGER;
 * SUPER_MANAGER → A05–A08; BRANCH_MANAGER → A06–A08 chi nhánh mình. Filter ngoài phạm vi → rỗng không query;
 * {@code branchId} khác của BRANCH_MANAGER → 403. {@link BranchScope} thật trên {@code SecurityContextHolder}.
 */
class StaffQueryServiceTest {

    private static final long OWN = 3L;
    private static final long OTHER = 4L;
    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");

    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final StaffPresence presence = mock(StaffPresence.class);
    private final StaffAdminMapper mapper = Mappers.getMapper(StaffAdminMapper.class);
    private final StaffQueryService service = new StaffQueryService(staffProfiles, new BranchScope(), mapper, presence);

    @BeforeEach
    void setUp() {
        when(presence.onlineSince()).thenReturn(NOW.minusSeconds(600));
        when(staffProfiles.findStaffPage(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(4, Pageable.class), 0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- #15 danh sách

    @Test
    void eachActorQueriesOnlyItsVisibleRolesAndBranch() {
        record Case(Role actor, Long actorBranch, Long requestedBranch, Set<Role> roles, Long branch) {}
        List<Case> cases = List.of(
                new Case(Role.ADMIN, null, null, EnumSet.of(Role.SUPER_MANAGER), null),
                new Case(Role.SUPER_MANAGER, null, null,
                        EnumSet.of(Role.BRANCH_MANAGER, Role.RECEPTIONIST, Role.VET, Role.CARETAKER), null),
                new Case(Role.SUPER_MANAGER, null, OTHER,
                        EnumSet.of(Role.BRANCH_MANAGER, Role.RECEPTIONIST, Role.VET, Role.CARETAKER), OTHER),
                new Case(Role.BRANCH_MANAGER, OWN, null, EnumSet.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER), OWN),
                new Case(Role.BRANCH_MANAGER, OWN, OWN, EnumSet.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER), OWN));
        for (Case c : cases) {
            org.mockito.Mockito.clearInvocations(staffProfiles);
            actAs(c.actor(), c.actorBranch());

            service.listStaff(c.requestedBranch(), null, null, null, 0, 20);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Role>> roles = ArgumentCaptor.forClass(java.util.Collection.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<AccountStatus>> statuses =
                    ArgumentCaptor.forClass(java.util.Collection.class);
            verify(staffProfiles).findStaffPage(roles.capture(), statuses.capture(), eq(c.branch()), eq("%"), any());
            assertThat(roles.getValue()).as(c.toString()).containsExactlyInAnyOrderElementsOf(c.roles());
            assertThat(statuses.getValue()).containsExactlyInAnyOrder(AccountStatus.ACTIVE, AccountStatus.DISABLED);
        }
    }

    @Test
    void roleFilterOutsideScopeReturnsEmptyPageWithoutQuery() {
        actAs(Role.ADMIN, null);
        PageResponse<StaffResponse> adminVet = service.listStaff(null, Role.VET, null, null, 2, 10);
        actAs(Role.SUPER_MANAGER, null);
        PageResponse<StaffResponse> smAdmin = service.listStaff(null, Role.ADMIN, null, null, 0, 20);
        actAs(Role.BRANCH_MANAGER, OWN);
        PageResponse<StaffResponse> bmBm = service.listStaff(null, Role.BRANCH_MANAGER, null, null, 0, 20);
        PageResponse<StaffResponse> pending = service.listStaff(null, null, AccountStatus.PENDING, null, 0, 20);

        assertThat(adminVet).isEqualTo(new PageResponse<StaffResponse>(List.of(), 2, 10, 0, 0));
        assertThat(List.of(smAdmin, bmBm, pending)).allSatisfy(page -> assertThat(page.content()).isEmpty());
        verify(staffProfiles, never()).findStaffPage(any(), any(), any(), any(), any());
    }

    @Test
    void branchManagerAskingOtherBranchIs403() {
        actAs(Role.BRANCH_MANAGER, OWN);

        assertThatThrownBy(() -> service.listStaff(OTHER, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedScopeException.class);
        verify(staffProfiles, never()).findStaffPage(any(), any(), any(), any(), any());
    }

    @Test
    void filtersPatternAndPagingArePassedThrough() {
        actAs(Role.SUPER_MANAGER, null);

        service.listStaff(null, Role.VET, AccountStatus.DISABLED, "  An_100%! ", -5, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(staffProfiles).findStaffPage(eq(EnumSet.of(Role.VET)), eq(EnumSet.of(AccountStatus.DISABLED)),
                eq(null), eq("%an!_100!%!!%"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(StaffQueryService.MAX_PAGE_SIZE);
    }

    @Test
    void likePatternBlankMeansAll() {
        assertThat(StaffQueryService.likePattern(null)).isEqualTo("%");
        assertThat(StaffQueryService.likePattern("   ")).isEqualTo("%");
        assertThat(StaffQueryService.likePattern("Bình")).isEqualTo("%bình%");
    }

    @Test
    void listMapsOnlineFromWindow() {
        actAs(Role.SUPER_MANAGER, null);
        StaffDetailRow onBoundary = row(Role.VET, OWN, NOW.minusSeconds(600));
        StaffDetailRow before = row(Role.VET, OWN, NOW.minusSeconds(601));
        doAnswer(inv -> new PageImpl<>(List.of(onBoundary, before), inv.getArgument(4, Pageable.class), 2))
                .when(staffProfiles).findStaffPage(any(), any(), any(), any(), any());

        PageResponse<StaffResponse> page = service.listStaff(null, null, null, null, 0, 20);

        assertThat(page.content()).extracting(StaffResponse::online).containsExactly(true, false);
        assertThat(page.totalElements()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- #17 chi tiết

    @Test
    void getReturnsVisibleStaff() {
        actAs(Role.BRANCH_MANAGER, OWN);
        StaffDetailRow row = row(Role.VET, OWN, null);
        when(staffProfiles.findStaffDetail(9L)).thenReturn(Optional.of(row));

        assertThat(service.getStaff(9L)).isEqualTo(mapper.toResponse(row, false));
    }

    @Test
    void getMissingIs404() {
        actAs(Role.ADMIN, null);
        when(staffProfiles.findStaffDetail(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStaff(9L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy nhân viên #9");
    }

    @Test
    void getOutsideScopeIs403() {
        record Case(Role actor, Long actorBranch, Role target, Long targetBranch) {}
        List<Case> cases = List.of(
                new Case(Role.ADMIN, null, Role.VET, OWN),
                new Case(Role.ADMIN, null, Role.ADMIN, null),
                new Case(Role.SUPER_MANAGER, null, Role.SUPER_MANAGER, null),
                new Case(Role.SUPER_MANAGER, null, Role.ADMIN, null),
                new Case(Role.BRANCH_MANAGER, OWN, Role.BRANCH_MANAGER, OWN),
                new Case(Role.BRANCH_MANAGER, OWN, Role.VET, OTHER),
                new Case(Role.BRANCH_MANAGER, OWN, Role.SUPER_MANAGER, null));
        for (Case c : cases) {
            actAs(c.actor(), c.actorBranch());
            when(staffProfiles.findStaffDetail(9L)).thenReturn(Optional.of(row(c.target(), c.targetBranch(), null)));

            assertThatThrownBy(() -> service.getStaff(9L)).as(c.toString())
                    .isInstanceOf(AccessDeniedScopeException.class);
        }
    }

    @Test
    void getVisibleForEachActor() {
        record Case(Role actor, Long actorBranch, Role target, Long targetBranch) {}
        List<Case> cases = List.of(
                new Case(Role.ADMIN, null, Role.SUPER_MANAGER, null),
                new Case(Role.SUPER_MANAGER, null, Role.BRANCH_MANAGER, OTHER),
                new Case(Role.SUPER_MANAGER, null, Role.CARETAKER, OWN),
                new Case(Role.BRANCH_MANAGER, OWN, Role.RECEPTIONIST, OWN));
        for (Case c : cases) {
            actAs(c.actor(), c.actorBranch());
            when(staffProfiles.findStaffDetail(9L)).thenReturn(Optional.of(row(c.target(), c.targetBranch(), null)));

            assertThat(service.getStaff(9L).role()).as(c.toString()).isEqualTo(c.target());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void actAs(Role role, Long branchId) {
        AccountPrincipal principal = new AccountPrincipal(1L, "actor@petcare.test", 42L, role, branchId, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static StaffDetailRow row(Role role, Long branchId, Instant lastSeenAt) {
        return new StaffDetailRow(9L, "nv@petcare.test", "0901234567", "Nhân viên", role, AccountStatus.ACTIVE,
                false, branchId, null, null, false, lastSeenAt);
    }
}
