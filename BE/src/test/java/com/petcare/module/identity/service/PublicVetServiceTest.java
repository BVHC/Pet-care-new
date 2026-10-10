package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.PublicVetProfile;
import com.petcare.module.identity.dto.PublicVetResponse;
import com.petcare.module.identity.mapper.PublicVetMapper;

/**
 * UC15 (BR-TK-20, BR-CK-01; docs/adr/0026): chỉ VET của chi nhánh {@code ACTIVE}, kèm tên chi nhánh; chi nhánh lạ /
 * chưa hoạt động → rỗng mà không đọc nhân viên; giữ thứ tự của {@code listPublicVets}.
 */
class PublicVetServiceTest {

    private static final long BRANCH_A = 10L;
    private static final long BRANCH_B = 20L;
    private static final long DRAFT_BRANCH = 30L;

    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final PublicVetService service = new PublicVetService(staffDirectory, branches,
            Mappers.getMapper(PublicVetMapper.class));

    @BeforeEach
    void setUp() {
        when(branches.listActiveBranches()).thenReturn(List.of(branch(BRANCH_A, "Chi nhánh A"),
                branch(BRANCH_B, "Chi nhánh B")));
        when(staffDirectory.listPublicVets()).thenReturn(List.of(
                vet(1L, "An", BRANCH_B),
                vet(2L, "Bình", DRAFT_BRANCH),
                vet(3L, "Chi", null),
                vet(4L, "Dung", BRANCH_A)));
    }

    @Test
    void onlyVetsOfActiveBranchesWithBranchNameInQueryOrder() {
        assertThat(service.listPublicVets(null)).containsExactly(
                new PublicVetResponse(1L, "An", "https://img/1.png", "Chuyên môn 1", "Bio 1", BRANCH_B, "Chi nhánh B"),
                new PublicVetResponse(4L, "Dung", "https://img/4.png", "Chuyên môn 4", "Bio 4", BRANCH_A,
                        "Chi nhánh A"));
    }

    @Test
    void filtersByRequestedActiveBranch() {
        assertThat(service.listPublicVets(BRANCH_A)).extracting(PublicVetResponse::accountId).containsExactly(4L);
    }

    @Test
    void draftOrUnknownBranchGivesEmptyListWithoutReadingStaff() {
        assertThat(service.listPublicVets(DRAFT_BRANCH)).isEmpty();
        assertThat(service.listPublicVets(999L)).isEmpty();
        verifyNoInteractions(staffDirectory);
    }

    @Test
    void noActiveBranchGivesEmptyList() {
        when(branches.listActiveBranches()).thenReturn(List.of());

        assertThat(service.listPublicVets(null)).isEmpty();
    }

    @Test
    void isReadOnlyTransaction() throws NoSuchMethodException {
        Transactional tx = PublicVetService.class.getMethod("listPublicVets", Long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
    }

    private static BranchSummary branch(long id, String name) {
        return new BranchSummary(id, name, "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false);
    }

    private static PublicVetProfile vet(long id, String name, Long branchId) {
        return new PublicVetProfile(id, name, "https://img/" + id + ".png", "Chuyên môn " + id, "Bio " + id,
                branchId);
    }
}
