package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.dto.BranchResponse;
import com.petcare.module.branch.dto.CreateBranchRequest;
import com.petcare.module.branch.dto.UpdateBranchRequest;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.fsm.BranchTransitionHandler;
import com.petcare.module.branch.mapper.BranchMapperImpl;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.OpeningHoursRepository;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/** UC12: Chi nhánh#1 (tạo → DRAFT), #2 (kích hoạt), BR-CN-01, BR-CN-05, BR-QT-04. */
@ExtendWith(MockitoExtension.class)
class BranchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    @Mock BranchRepository branches;
    @Mock OpeningHoursRepository openingHours;
    @Mock StaffDirectoryApi staff;
    @Mock BranchScope scope;

    BranchService service;

    @BeforeEach
    void setUp() {
        service = new BranchService(branches, openingHours, staff, new BranchTransitionHandler(), scope,
                new BranchMapperImpl(), Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static CreateBranchRequest create(String name, Boolean emergency) {
        return new CreateBranchRequest(name, " 1 Nguyễn Huệ ", " 0281234567 ", new BigDecimal("10.776889"),
                new BigDecimal("106.700806"), emergency);
    }

    private Branch existing(BranchStatus status) {
        Branch branch = new Branch("CN Quận 1", "Địa chỉ", "0281234567", new BigDecimal("10.7"),
                new BigDecimal("106.7"), false);
        ReflectionTestUtils.setField(branch, "id", 3L);
        ReflectionTestUtils.setField(branch, "status", status);
        return branch;
    }

    private static void assertRule(Throwable thrown, String ruleId) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo(ruleId));
    }

    // -------------------------------------------------------------- tạo (Chi nhánh#1)

    @Test
    void createsADraftBranchWithTrimmedFields() {
        when(branches.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchResponse response = service.createBranch(create(" CN Quận 1 ", null));

        assertThat(response.name()).isEqualTo("CN Quận 1");
        assertThat(response.address()).isEqualTo("1 Nguyễn Huệ");
        assertThat(response.phone()).isEqualTo("0281234567");
        assertThat(response.status()).isEqualTo(BranchStatus.DRAFT);
        assertThat(response.acceptsAfterHoursEmergency()).isFalse();
        assertThat(response.activatedAt()).isNull();
    }

    @Test
    void createsWithTheEmergencyFlagWhenAsked() {
        when(branches.save(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.createBranch(create("CN 2", true)).acceptsAfterHoursEmergency()).isTrue();
    }

    @Test
    void duplicateNameIsRejected_BR_CN_01() {
        when(branches.existsByName("CN Quận 1")).thenReturn(true);

        assertThatThrownBy(() -> service.createBranch(create("CN Quận 1", null)))
                .satisfies(t -> assertRule(t, "BR-CN-01"));
        verify(branches, never()).save(any());
    }

    // -------------------------------------------------------------- sửa

    @Test
    void updatesOnlyGivenFieldsIncludingTheEmergencyFlag_BR_CN_05() {
        Branch branch = existing(BranchStatus.ACTIVE);
        when(branches.findById(3L)).thenReturn(Optional.of(branch));
        when(branches.saveAndFlush(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchResponse response = service.updateBranch(3L,
                new UpdateBranchRequest(null, null, "0289999999", null, null, true));

        assertThat(response.phone()).isEqualTo("0289999999");
        assertThat(response.acceptsAfterHoursEmergency()).isTrue();
        assertThat(response.name()).isEqualTo("CN Quận 1");
        assertThat(response.status()).isEqualTo(BranchStatus.ACTIVE);
    }

    @Test
    void renameChecksDuplicateExcludingSelf() {
        when(branches.findById(3L)).thenReturn(Optional.of(existing(BranchStatus.DRAFT)));
        when(branches.existsByNameAndIdNot("CN Quận 7", 3L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateBranch(3L,
                new UpdateBranchRequest("CN Quận 7", null, null, null, null, null)))
                .satisfies(t -> assertRule(t, "BR-CN-01"));
    }

    @Test
    void updateUnknownBranchIsNotFound() {
        when(branches.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBranch(3L, new UpdateBranchRequest(null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // -------------------------------------------------------------- đọc

    @Test
    void getChecksTheBranchScope() {
        Branch branch = existing(BranchStatus.ACTIVE);
        when(branches.findById(3L)).thenReturn(Optional.of(branch));

        assertThat(service.getBranch(3L).branchId()).isEqualTo(3L);

        verify(scope).check(3L);
    }

    @Test
    void getUnknownBranchIsNotFound() {
        when(branches.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBranch(3L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(scope);
    }

    @Test
    void listResolvesTheScopeOnce() {
        when(scope.resolve(null)).thenReturn(7L);
        when(branches.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(existing(BranchStatus.ACTIVE)));

        assertThat(service.listBranches(BranchStatus.ACTIVE)).hasSize(1);

        verify(scope).resolve(null);
    }

    // -------------------------------------------------------------- kích hoạt (Chi nhánh#2)

    @Test
    void activatesWhenAManagerAndOpeningHoursExist() {
        Branch branch = existing(BranchStatus.DRAFT);
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch));
        when(staff.countBranchManagers(3L)).thenReturn(1);
        when(openingHours.existsByBranchIdAndOpen1IsNotNull(3L)).thenReturn(true);
        when(branches.saveAndFlush(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        BranchResponse response = service.activateBranch(3L);

        assertThat(response.status()).isEqualTo(BranchStatus.ACTIVE);
        assertThat(response.activatedAt()).isEqualTo(NOW);
    }

    @Test
    void missingManagerIsReportedAsBR_QT_04() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(existing(BranchStatus.DRAFT)));
        when(staff.countBranchManagers(3L)).thenReturn(0);
        when(openingHours.existsByBranchIdAndOpen1IsNotNull(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.activateBranch(3L)).satisfies(t -> {
            assertRule(t, "BR-QT-04");
            assertThat(t.getMessage()).contains("chưa có BRANCH_MANAGER").doesNotContain("giờ mở cửa");
        });
        verify(branches, never()).saveAndFlush(any());
    }

    @Test
    void missingOpeningHoursIsReportedAsBR_CN_01() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(existing(BranchStatus.DRAFT)));
        when(staff.countBranchManagers(3L)).thenReturn(2);
        when(openingHours.existsByBranchIdAndOpen1IsNotNull(3L)).thenReturn(false);

        assertThatThrownBy(() -> service.activateBranch(3L)).satisfies(t -> {
            assertRule(t, "BR-CN-01");
            assertThat(t.getMessage()).contains("chưa cấu hình giờ mở cửa").doesNotContain("BRANCH_MANAGER");
        });
    }

    @Test
    void messageListsEveryMissingCondition() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(existing(BranchStatus.DRAFT)));
        when(staff.countBranchManagers(3L)).thenReturn(0);
        when(openingHours.existsByBranchIdAndOpen1IsNotNull(3L)).thenReturn(false);

        assertThatThrownBy(() -> service.activateBranch(3L)).satisfies(t -> {
            assertRule(t, "BR-QT-04");
            assertThat(t.getMessage()).contains("chưa có BRANCH_MANAGER").contains("chưa cấu hình giờ mở cửa");
        });
    }

    @Test
    void activeBranchIsRejectedBeforeAnyGuardIsEvaluated() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(existing(BranchStatus.ACTIVE)));

        assertThatThrownBy(() -> service.activateBranch(3L)).isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(staff, openingHours);
    }

    @Test
    void activationTakesTheRowLockBeforeReadingAnything() {
        Branch branch = existing(BranchStatus.DRAFT);
        lenient().when(branches.findByIdForUpdate(3L)).thenReturn(Optional.of(branch));
        when(staff.countBranchManagers(3L)).thenReturn(1);
        when(openingHours.existsByBranchIdAndOpen1IsNotNull(3L)).thenReturn(true);
        when(branches.saveAndFlush(any(Branch.class))).thenAnswer(inv -> inv.getArgument(0));

        service.activateBranch(3L);

        InOrder order = inOrder(branches, staff);
        order.verify(branches).findByIdForUpdate(3L);
        order.verify(staff).countBranchManagers(3L);
    }

    @Test
    void activateUnknownBranchIsNotFound() {
        when(branches.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateBranch(3L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
