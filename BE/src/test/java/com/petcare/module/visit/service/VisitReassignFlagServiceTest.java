package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * {@link VisitReassignFlagService} (D017, docs/adr/0062): {@code MANDATORY}, {@code updated_at} từ {@code Clock} cắt về
 * µs, một câu UPDATE theo người bị khóa; repository không xóa persistence context của transaction khóa (F3).
 */
class VisitReassignFlagServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T02:15:30.123456789Z");

    private final VisitRepository visits = mock(VisitRepository.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final VisitReassignFlagService service = new VisitReassignFlagService(visits, staffDirectory,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));
    private static final Instant AT = NOW.truncatedTo(ChronoUnit.MICROS);

    @Test
    void flagsTheLockedAccountsCalledVisitsAtTheClockInstant() {
        doReturn(2).when(visits).flagNeedsReassign(45L, NOW.truncatedTo(ChronoUnit.MICROS));

        assertThat(service.flagCalledVisitsOf(45L)).isEqualTo(2);

        verify(visits).flagNeedsReassign(45L, NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void nullAccountIsAProgrammingError() {
        assertThatThrownBy(() -> service.flagCalledVisitsOf(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(visits);
    }

    @Test
    void serviceAndRepositoryJoinTheLockTransaction() throws Exception {
        Method serviceMethod = VisitReassignFlagService.class.getMethod("flagCalledVisitsOf", Long.class);
        assertThat(serviceMethod.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);

        Method repositoryMethod = VisitRepository.class.getMethod("flagNeedsReassign", Long.class, Instant.class);
        assertThat(repositoryMethod.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        Modifying modifying = repositoryMethod.getAnnotation(Modifying.class);
        assertThat(modifying.flushAutomatically()).as("ghi Account đang sửa trước câu native").isTrue();
        assertThat(modifying.clearAutomatically()).as("không tách entity của identity (F3)").isFalse();
    }

    private void staff(Role role, Long branchId, boolean active) {
        when(staffDirectory.findStaff(45L)).thenReturn(
                Optional.of(new StaffSummary(45L, "Bác sĩ A", role, branchId, active, false)));
    }

    @Test
    void unlockedVetClearsOnlyMedicalVisitsOfTheirOwnBranch() {
        staff(Role.VET, 3L, true);
        doReturn(2).when(visits).clearNeedsReassign(45L, 3L, "MEDICAL", AT);

        assertThat(service.clearFlagsOf(45L)).isEqualTo(2);

        verify(visits).clearNeedsReassign(45L, 3L, "MEDICAL", AT);
        verify(visits, never()).clearNeedsReassign(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("GROOMING"),
                any());
    }

    @Test
    void unlockedCaretakerClearsOnlyGroomingVisits() {
        staff(Role.CARETAKER, 3L, true);
        doReturn(1).when(visits).clearNeedsReassign(45L, 3L, "GROOMING", AT);

        assertThat(service.clearFlagsOf(45L)).isEqualTo(1);

        verify(visits, never()).clearNeedsReassign(anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("MEDICAL"),
                any());
    }

    @Test
    void anAccountThatCannotTakeVisitsKeepsEveryFlag() {
        // Mở khóa mà vẫn DISABLED / không có chi nhánh / chức vụ không phụ trách lượt: giữ cờ để #9 gán lại (BR-TN-08).
        staff(Role.VET, 3L, false);
        assertThat(service.clearFlagsOf(45L)).isZero();
        staff(Role.VET, null, true);
        assertThat(service.clearFlagsOf(45L)).isZero();
        staff(Role.RECEPTIONIST, 3L, true);
        assertThat(service.clearFlagsOf(45L)).isZero();
        when(staffDirectory.findStaff(45L)).thenReturn(Optional.empty());
        assertThat(service.clearFlagsOf(45L)).isZero();

        verify(visits, never()).clearNeedsReassign(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void clearIsMandatoryAndFlushesButDoesNotClearTheContext() throws Exception {
        Method serviceMethod = VisitReassignFlagService.class.getMethod("clearFlagsOf", Long.class);
        assertThat(serviceMethod.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);

        Method repositoryMethod = VisitRepository.class.getMethod("clearNeedsReassign", Long.class, Long.class,
                String.class, Instant.class);
        assertThat(repositoryMethod.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        Modifying modifying = repositoryMethod.getAnnotation(Modifying.class);
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isFalse();
    }
}
