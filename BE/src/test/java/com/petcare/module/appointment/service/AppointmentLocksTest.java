package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;

/**
 * {@link AppointmentLocks} (docs/adr/0045): tham số sai / transaction readOnly bị chặn trước mọi câu SQL; key advisory
 * nằm trong {@code int} dương. Khóa thật trên PostgreSQL: {@code AppointmentLockIT}.
 */
class AppointmentLocksTest {

    private final AppointmentRepository repository = mock(AppointmentRepository.class);
    private final AppointmentLocks locks = new AppointmentLocks(repository);

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void locksPetInItsNamespace() {
        locks.lockPet(42L);

        verify(repository).acquireTransactionLock(AppointmentLocks.PET_LOCK_NAMESPACE, 42);
    }

    /** Khóa theo khách khi đánh giá BR-LH-09 (docs/adr/0049): namespace khác thú, cùng các chốt. */
    @Test
    void locksCustomerViolationsInItsOwnNamespace() throws NoSuchMethodException {
        locks.lockCustomerViolations(88L);

        verify(repository).acquireTransactionLock(AppointmentLocks.CUSTOMER_VIOLATION_NAMESPACE, 88);
        assertThat(AppointmentLocks.CUSTOMER_VIOLATION_NAMESPACE).isNotEqualTo(AppointmentLocks.PET_LOCK_NAMESPACE);
        assertThatThrownBy(() -> locks.lockCustomerViolations(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> locks.lockCustomerViolations(0L)).isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> locks.lockCustomerViolations(1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        Transactional tx = AppointmentLocks.class.getMethod("lockCustomerViolations", Long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(tx.noRollbackFor()).isEmpty();
    }

    @Test
    void keyStaysAPositiveIntForAnyPositiveId() {
        assertThat(AppointmentLocks.petKey(1L)).isEqualTo(1);
        assertThat(AppointmentLocks.petKey(Integer.MAX_VALUE - 1L)).isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(AppointmentLocks.petKey(Integer.MAX_VALUE)).isZero();
        assertThat(AppointmentLocks.petKey(Long.MAX_VALUE)).isBetween(0, Integer.MAX_VALUE);
    }

    @Test
    void invalidIdsAreRejectedBeforeAnySql() {
        assertThatThrownBy(() -> locks.lockPet(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> locks.lockPet(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> locks.lockPet(-5L)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).acquireTransactionLock(anyInt(), anyInt());
    }

    @Test
    void readOnlyTransactionIsRejectedBeforeAnySql() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> locks.lockPet(1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        verify(repository, never()).acquireTransactionLock(anyInt(), anyInt());
    }

    /** Khóa xact chỉ có nghĩa trong transaction của bên gọi; không lớp nào được tự mở transaction hay nuốt rollback. */
    @Test
    void lockMethodsAreMandatoryWithoutNoRollbackFor() throws NoSuchMethodException {
        Transactional service = AppointmentLocks.class.getMethod("lockPet", Long.class)
                .getAnnotation(Transactional.class);
        Transactional repo = AppointmentRepository.class.getMethod("acquireTransactionLock", int.class, int.class)
                .getAnnotation(Transactional.class);

        for (Transactional tx : new Transactional[] {service, repo}) {
            assertThat(tx).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.noRollbackFor()).isEmpty();
        }
    }
}
