package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.ReactivateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Facade quản trị nhân viên (docs/adr/0029 mục 2, docs/adr/0030, 0031): sinh mật khẩu trước transaction (tạo, gửi lại;
 * đổi chức vụ, điều chuyển không cần mật khẩu); chỉ lỗi vượt phân
 * cấp được ghi audit, bằng {@code recordIndependently}, sau khi service đã ném (tức transaction đã rollback), rồi ném
 * lại đúng instance đó. Facade không được có {@code @Transactional}.
 */
class StaffAdminServiceTest {

    private static final TemporaryPassword TEMP = new TemporaryPassword("Abc23456defg", "$2a$hash");
    private static final CreateStaffRequest REQUEST = new CreateStaffRequest("a@b.c", "0901234567", "A", "VET", 3L);

    private final StaffAccountService staffAccounts = mock(StaffAccountService.class);
    private final TemporaryPasswordGenerator generator = mock(TemporaryPasswordGenerator.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final StaffAdminService facade = new StaffAdminService(staffAccounts, generator, audit);

    @BeforeEach
    void setUp() {
        when(generator.prepare()).thenReturn(TEMP);
    }

    @Test
    void facadeIsNotTransactional() {
        assertThat(StaffAdminService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(StaffAdminService.class.getDeclaredMethods())
                .filter((Method method) -> method.isAnnotationPresent(Transactional.class))).isEmpty();
    }

    @Test
    void createPreparesPasswordBeforeTransactionAndPassesItThrough() {
        facade.createStaff(REQUEST);

        InOrder order = inOrder(generator, staffAccounts);
        order.verify(generator).prepare();
        order.verify(staffAccounts).createStaff(REQUEST, TEMP);
        verifyNoInteractions(audit);
    }

    @Test
    void hierarchyDenialIsAuditedIndependentlyThenRethrownAsIs() {
        AuditEntry entry = AuditEntry.of(QtAuditActions.STAFF_ACTION_DENIED);
        StaffHierarchyDeniedException denied = new StaffHierarchyDeniedException("STAFF_HIERARCHY CREATE", "VET",
                entry);
        when(staffAccounts.createStaff(REQUEST, TEMP)).thenThrow(denied);
        doThrow(denied).when(staffAccounts).resendInitialPassword(7L, TEMP);

        assertThatThrownBy(() -> facade.createStaff(REQUEST)).isSameAs(denied);
        assertThatThrownBy(() -> facade.resendInitialPassword(7L)).isSameAs(denied);

        InOrder order = inOrder(staffAccounts, audit);
        order.verify(staffAccounts).createStaff(REQUEST, TEMP);
        order.verify(audit).recordIndependently(entry);
        order.verify(staffAccounts).resendInitialPassword(7L, TEMP);
        order.verify(audit).recordIndependently(entry);
        verify(audit, org.mockito.Mockito.never()).record(any());
    }

    @Test
    void otherErrorsAreNotAudited() {
        when(staffAccounts.createStaff(any(), any())).thenThrow(new BusinessRuleViolationException("BR-TK-01", "x"));
        doThrow(new InvalidStateTransitionException("a", "b", "c")).when(staffAccounts)
                .resendInitialPassword(anyLong(), any());

        assertThatThrownBy(() -> facade.createStaff(REQUEST)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> facade.resendInitialPassword(7L)).isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void changeRoleAndTransferPassThroughWithoutPasswordOrAudit() {
        ChangeRoleRequest changeRole = new ChangeRoleRequest("VET");
        TransferStaffRequest transfer = new TransferStaffRequest(4L);
        StaffResponse response = mock(StaffResponse.class);
        when(staffAccounts.changeStaffRole(7L, changeRole)).thenReturn(response);
        when(staffAccounts.transferStaff(7L, transfer)).thenReturn(response);

        assertThat(facade.changeStaffRole(7L, changeRole)).isSameAs(response);
        assertThat(facade.transferStaff(7L, transfer)).isSameAs(response);
        verifyNoInteractions(generator, audit);
    }

    @Test
    void changeRoleAndTransferHierarchyDenialIsAuditedAfterThrowThenRethrownAsIs() {
        ChangeRoleRequest changeRole = new ChangeRoleRequest("VET");
        TransferStaffRequest transfer = new TransferStaffRequest(4L);
        AuditEntry entry = AuditEntry.of(QtAuditActions.STAFF_ACTION_DENIED);
        StaffHierarchyDeniedException denied = new StaffHierarchyDeniedException("STAFF_HIERARCHY CHANGE_ROLE",
                "SUPER_MANAGER", entry);
        when(staffAccounts.changeStaffRole(7L, changeRole)).thenThrow(denied);
        when(staffAccounts.transferStaff(7L, transfer)).thenThrow(denied);

        assertThatThrownBy(() -> facade.changeStaffRole(7L, changeRole)).isSameAs(denied);
        assertThatThrownBy(() -> facade.transferStaff(7L, transfer)).isSameAs(denied);

        InOrder order = inOrder(staffAccounts, audit);
        order.verify(staffAccounts).changeStaffRole(7L, changeRole);
        order.verify(audit).recordIndependently(entry);
        order.verify(staffAccounts).transferStaff(7L, transfer);
        order.verify(audit).recordIndependently(entry);
        verify(audit, org.mockito.Mockito.never()).record(any());
        verifyNoInteractions(generator);
    }

    @Test
    void changeRoleAndTransferOtherErrorsAreNotAudited() {
        when(staffAccounts.changeStaffRole(anyLong(), any()))
                .thenThrow(new BusinessRuleViolationException("BR-QT-04", "x"))
                .thenThrow(new ResourceNotFoundException("nhân viên", 7L));
        when(staffAccounts.transferStaff(anyLong(), any()))
                .thenThrow(new ConcurrencyConflictException("nhân viên", 7L))
                .thenThrow(new UnsupportedOperationException("placeholder"));

        assertThatThrownBy(() -> facade.changeStaffRole(7L, new ChangeRoleRequest("VET")))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> facade.changeStaffRole(7L, new ChangeRoleRequest("VET")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> facade.transferStaff(7L, new TransferStaffRequest(4L)))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertThatThrownBy(() -> facade.transferStaff(7L, new TransferStaffRequest(4L)))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(audit);
    }

    // ---------------------------------------------------------------- #20, #21 (docs/adr/0033)

    @Test
    void disablePassesThroughWithoutPasswordOrAudit() {
        StaffResponse response = mock(StaffResponse.class);
        when(staffAccounts.disableStaff(7L)).thenReturn(response);

        assertThat(facade.disableStaff(7L)).isSameAs(response);
        verifyNoInteractions(generator, audit);
    }

    @Test
    void reactivatePreparesPasswordBeforeTransactionAndPassesRequestThroughIncludingNull() {
        StaffResponse response = mock(StaffResponse.class);
        ReactivateStaffRequest request = new ReactivateStaffRequest(4L);
        when(staffAccounts.reactivateStaff(7L, request, TEMP)).thenReturn(response);
        when(staffAccounts.reactivateStaff(8L, null, TEMP)).thenReturn(response);

        assertThat(facade.reactivateStaff(7L, request)).isSameAs(response);
        assertThat(facade.reactivateStaff(8L, null)).isSameAs(response);

        InOrder order = inOrder(generator, staffAccounts);
        order.verify(generator).prepare();
        order.verify(staffAccounts).reactivateStaff(7L, request, TEMP);
        order.verify(generator).prepare();
        order.verify(staffAccounts).reactivateStaff(8L, null, TEMP);
        verifyNoInteractions(audit);
    }

    @Test
    void disableAndReactivateHierarchyDenialIsAuditedAfterThrowThenRethrownAsIs() {
        AuditEntry entry = AuditEntry.of(QtAuditActions.STAFF_ACTION_DENIED);
        StaffHierarchyDeniedException denied = new StaffHierarchyDeniedException("STAFF_HIERARCHY DISABLE",
                "BRANCH_MANAGER", entry);
        when(staffAccounts.disableStaff(7L)).thenThrow(denied);
        when(staffAccounts.reactivateStaff(7L, null, TEMP)).thenThrow(denied);

        assertThatThrownBy(() -> facade.disableStaff(7L)).isSameAs(denied);
        assertThatThrownBy(() -> facade.reactivateStaff(7L, null)).isSameAs(denied);

        InOrder order = inOrder(staffAccounts, audit);
        order.verify(staffAccounts).disableStaff(7L);
        order.verify(audit).recordIndependently(entry);
        order.verify(staffAccounts).reactivateStaff(7L, null, TEMP);
        order.verify(audit).recordIndependently(entry);
        verify(audit, org.mockito.Mockito.never()).record(any());
    }

    @Test
    void disableAndReactivateOtherErrorsAreNotAudited() {
        when(staffAccounts.disableStaff(anyLong()))
                .thenThrow(new BusinessRuleViolationException("BR-QT-12", "x"))
                .thenThrow(new InvalidStateTransitionException("tài khoản", "DISABLED", "DISABLED"))
                .thenThrow(new UnsupportedOperationException("placeholder"));
        when(staffAccounts.reactivateStaff(anyLong(), any(), any()))
                .thenThrow(new BusinessRuleViolationException("BR-QT-10", "x"))
                .thenThrow(new ResourceNotFoundException("nhân viên", 7L));

        assertThatThrownBy(() -> facade.disableStaff(7L)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> facade.disableStaff(7L)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> facade.disableStaff(7L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facade.reactivateStaff(7L, null)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> facade.reactivateStaff(7L, null)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(audit);
    }
}
