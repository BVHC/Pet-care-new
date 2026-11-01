package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.boarding.dto.CreateKennelRequest;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class KennelServiceTest {

    private static final long BRANCH = 7L;

    private BranchScope scope;
    private KennelRepository kennels;
    private BoardingBookingRepository bookings;
    private BranchApi branchLocks;
    private BranchQueryApi branches;
    private CatalogQueryApi catalog;
    private KennelService service;

    @BeforeEach
    void setUp() {
        scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.branchId()).thenReturn(BRANCH);
        when(scope.current()).thenReturn(principal);
        kennels = mock(KennelRepository.class);
        bookings = mock(BoardingBookingRepository.class);
        branchLocks = mock(BranchApi.class);
        branches = mock(BranchQueryApi.class);
        catalog = mock(CatalogQueryApi.class);
        service = new KennelService(scope, kennels, bookings, branchLocks, branches, catalog,
                mock(BoardingCapacity.class), mock(PetQueryApi.class), new KennelTransitionHandler(),
                Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC));
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.ACTIVE));
    }

    private void kennelType(boolean active, boolean enabled) {
        when(catalog.findKennelType(5L)).thenReturn(Optional.of(
                new KennelTypeInfo(5L, "Chuồng nhỏ", Species.DOG, new BigDecimal("30"), 150_000L, active)));
        when(branches.isServiceEnabled(BRANCH, 5L)).thenReturn(enabled);
    }

    @Test
    void createLocksBranchBeforeCheckingDuplicateThenWrites() {
        kennelType(true, true);
        when(kennels.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.createKennel(new CreateKennelRequest(5L, " C-01 ", null));

        InOrder order = inOrder(branchLocks, kennels);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(kennels).existsByBranchIdAndCode(BRANCH, "C-01");
        order.verify(kennels).saveAndFlush(any(Kennel.class));
    }

    @Test
    void createRejectsDisabledKennelTypeAndWritesNothing() {
        kennelType(true, false);

        assertThatThrownBy(() -> service.createKennel(new CreateKennelRequest(5L, "C-01", null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-LT-01");

        verify(kennels, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsDuplicateCode() {
        kennelType(true, true);
        when(kennels.existsByBranchIdAndCode(BRANCH, "C-01")).thenReturn(true);

        assertThatThrownBy(() -> service.createKennel(new CreateKennelRequest(5L, "C-01", null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-LT-01");

        verify(kennels, never()).saveAndFlush(any());
    }

    private Kennel kennelWithStatus(KennelStatus status) {
        Kennel kennel = Kennel.create(BRANCH, 5L, "C-01", null);
        if (status == KennelStatus.MAINTENANCE) {
            kennel.startMaintenance();
        }
        if (status == KennelStatus.OCCUPIED) {
            kennel.occupy();
        }
        when(kennels.findBranchIdById(anyLong())).thenReturn(Optional.of(BRANCH));
        when(kennels.findByIdForUpdate(anyLong())).thenReturn(Optional.of(kennel));
        return kennel;
    }

    @Test
    void deleteOccupiedIs409AndWritesNothing() {
        Kennel kennel = kennelWithStatus(KennelStatus.AVAILABLE);
        org.springframework.test.util.ReflectionTestUtils.setField(kennel, "status", KennelStatus.OCCUPIED);

        assertThatThrownBy(() -> service.deleteKennel(1L)).isInstanceOf(BoardingConflictException.class);

        verify(kennels, never()).delete(any());
    }

    @Test
    void deleteWithHistoryIs409() {
        kennelWithStatus(KennelStatus.AVAILABLE);
        when(kennels.hasBookingHistory(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteKennel(1L)).isInstanceOf(BoardingConflictException.class)
                .hasMessageContaining("lịch sử lưu trú");

        verify(kennels, never()).delete(any());
    }

    @Test
    void deleteLocksBranchThenKennelThenDeletes() {
        Kennel kennel = kennelWithStatus(KennelStatus.AVAILABLE);

        service.deleteKennel(1L);

        InOrder order = inOrder(scope, branchLocks, kennels);
        order.verify(scope).check(BRANCH);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(kennels).findByIdForUpdate(1L);
        order.verify(kennels).delete(kennel);
    }

    /** Chuồng#5 chỉ đi từ MAINTENANCE: chuồng đang có thú không được "hết bảo trì" (T45). */
    @Test
    void endMaintenanceOnOccupiedKennelIsInvalidTransition() {
        Kennel kennel = kennelWithStatus(KennelStatus.OCCUPIED);

        assertThatThrownBy(() -> service.endMaintenance(1L))
                .isInstanceOf(com.petcare.platform.exception.InvalidStateTransitionException.class);

        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.OCCUPIED);
        verify(kennels, never()).saveAndFlush(any());
    }

    @Test
    void startMaintenanceOnMaintenanceKennelIsInvalidTransition() {
        kennelWithStatus(KennelStatus.MAINTENANCE);

        assertThatThrownBy(() -> service.startMaintenance(1L))
                .isInstanceOf(com.petcare.platform.exception.InvalidStateTransitionException.class);

        verify(kennels, never()).saveAndFlush(any());
        assertThat(true).isTrue();
    }
}
