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

import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;

/**
 * Facade quản trị nhân viên (docs/adr/0029 mục 2, docs/adr/0030): sinh mật khẩu trước transaction; chỉ lỗi vượt phân
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
}
