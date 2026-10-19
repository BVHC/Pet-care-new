package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.BookingRestrictionResponse;
import com.petcare.module.appointment.dto.RestrictionStatusResponse;
import com.petcare.module.appointment.entity.BookingRestriction;
import com.petcare.module.appointment.mapper.BookingRestrictionMapper;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link BookingRestrictionStatusService} (docs/adr/0050). Gỡ sớm: khách tồn tại → khóa 1002 → {@code now} → đọc dòng hiệu
 * lực → gỡ → {@code saveAndFlush} → audit; nhánh lỗi không ghi, không audit; 404 không lấy khóa; {@code now} sau khóa, cắt
 * µs; transaction mặc định (READ COMMITTED, không {@code noRollbackFor}). Xem: cùng điều kiện "đang hiệu lực" với guard đặt
 * lịch, khách chỉ thấy hồ sơ của mình.
 */
class BookingRestrictionStatusServiceTest {

    private static final long CUSTOMER = 88L;
    private static final long ACCOUNT = 7L;
    private static final long STAFF = 3L;
    private static final Instant NOW_NANOS = Instant.parse("2026-10-20T03:00:00.123456789Z");
    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00.123456Z");
    private static final Instant STARTS = Instant.parse("2026-10-15T02:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-11-14T02:00:00Z");

    private final BookingRestrictionRepository restrictions = mock(BookingRestrictionRepository.class);
    private final AppointmentLocks locks = mock(AppointmentLocks.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BookingRestrictionMapper mapper = Mappers.getMapper(BookingRestrictionMapper.class);
    private final Clock clock = mock(Clock.class);
    private final BookingRestrictionStatusService service = new BookingRestrictionStatusService(restrictions, locks,
            customers, branchScope, audit, mapper, clock);

    @BeforeEach
    void defaults() {
        when(clock.instant()).thenReturn(NOW_NANOS);
        when(branchScope.current()).thenReturn(principal);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(
                new CustomerContact(CUSTOMER, "Nguyễn Văn An", "0901234567", null, null, false)));
    }

    // ================================================================ #9 gỡ sớm

    @Test
    void liftLocksThenReadsUnderTheLockThenWritesThenAudits() {
        when(principal.accountId()).thenReturn(STAFF);
        BookingRestriction active = active(41L);
        when(restrictions.findActive(CUSTOMER, NOW)).thenReturn(Optional.of(active));

        RestrictionStatusResponse response = service.liftRestriction(CUSTOMER, "Khách vắng có lý do");

        assertThat(response).isEqualTo(new RestrictionStatusResponse(false, null));
        InOrder order = inOrder(customers, locks, clock, restrictions, audit);
        order.verify(customers).findContact(CUSTOMER);
        order.verify(locks).lockCustomerViolations(CUSTOMER);
        order.verify(clock).instant();
        order.verify(restrictions).findActive(CUSTOMER, NOW);
        order.verify(restrictions).saveAndFlush(active);
        order.verify(audit).record(any(AuditEntry.class));
        order.verifyNoMoreInteractions();
        assertThat(active.getLiftedAt()).as("cắt µs như TIMESTAMPTZ").isEqualTo(NOW);
        assertThat(active.getLiftedBy()).as("người gọi, không lấy từ body").isEqualTo(STAFF);
        assertThat(active.getLiftReason()).isEqualTo("Khách vắng có lý do");
        assertThat(active.getEndsAt()).as("không đổi khi gỡ").isEqualTo(ENDS);
    }

    @Test
    void liftAuditNamesTheRowAndCarriesBeforeAfterAndReason() {
        when(principal.accountId()).thenReturn(STAFF);
        when(restrictions.findActive(CUSTOMER, NOW)).thenReturn(Optional.of(active(41L)));

        service.liftRestriction(CUSTOMER, "Khách vắng có lý do");

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("liftedAt", null);
        before.put("liftedBy", null);
        assertThat(entry.getValue().action()).isEqualTo("BOOKING_RESTRICTION_LIFTED");
        assertThat(entry.getValue().entityType()).isEqualTo("booking_restrictions");
        assertThat(entry.getValue().entityId()).isEqualTo(41L);
        assertThat(entry.getValue().before()).isEqualTo(before);
        assertThat(entry.getValue().after()).isEqualTo(Map.of("liftedAt", NOW, "liftedBy", STAFF));
        assertThat(entry.getValue().reason()).isEqualTo("Khách vắng có lý do");
        assertThat(entry.getValue().actorOverridden()).as("actor lấy từ principal").isFalse();
    }

    @Test
    void unknownCustomerIs404BeforeAnyLockOrWrite() {
        when(customers.findContact(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.liftRestriction(999L, "x"))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy khách hàng #999");
        verifyNoInteractions(locks, restrictions, audit);
    }

    @Test
    void noActiveRestrictionUnderTheLockIs409AndWritesNothing() {
        when(restrictions.findActive(CUSTOMER, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.liftRestriction(CUSTOMER, "x"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái hạn chế đặt online từ không còn hiệu lực sang gỡ sớm");
        verify(locks).lockCustomerViolations(CUSTOMER);
        verify(restrictions, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void lockFailureStopsBeforeReadingOrWriting() {
        doThrow(new IllegalStateException("khóa lỗi")).when(locks).lockCustomerViolations(CUSTOMER);

        assertThatThrownBy(() -> service.liftRestriction(CUSTOMER, "x")).hasMessage("khóa lỗi");
        verifyNoInteractions(restrictions, audit);
    }

    @Test
    void flushFailureSurfacesBeforeTheAudit() {
        BookingRestriction active = active(41L);
        when(restrictions.findActive(CUSTOMER, NOW)).thenReturn(Optional.of(active));
        when(restrictions.saveAndFlush(active)).thenThrow(new IllegalStateException("UPDATE lỗi"));

        assertThatThrownBy(() -> service.liftRestriction(CUSTOMER, "x")).hasMessage("UPDATE lỗi");
        verifyNoInteractions(audit);
    }

    @Test
    void liftIsOneDefaultTransactionThatRollsBackOnEveryError() throws NoSuchMethodException {
        Transactional tx = BookingRestrictionStatusService.class
                .getMethod("liftRestriction", Long.class, String.class).getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).as("READ COMMITTED: đọc sau khóa phải thấy hạn chế vừa commit")
                .isEqualTo(Isolation.DEFAULT);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ #7, #8 xem

    @Test
    void customerSeesTheActiveRestrictionOfTheirOwnProfile() {
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(restrictions.findActive(CUSTOMER, NOW_NANOS)).thenReturn(Optional.of(active(41L)));

        assertThat(service.getMyStatus()).isEqualTo(new RestrictionStatusResponse(true,
                new BookingRestrictionResponse(41L, CUSTOMER, STARTS, ENDS, 3)));
        verifyNoInteractions(locks, audit);
        verify(customers, never()).findContact(any());
    }

    @Test
    void customerWithoutRestrictionSeesFalseAndNull() {
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(restrictions.findActive(CUSTOMER, NOW_NANOS)).thenReturn(Optional.empty());

        assertThat(service.getMyStatus()).isEqualTo(new RestrictionStatusResponse(false, null));
    }

    @Test
    void customerAccountWithoutProfileIsBadData() {
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMyStatus).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(restrictions);
    }

    @Test
    void staffViewNeedsAnExistingCustomerAndNeverLocks() {
        when(restrictions.findActive(CUSTOMER, NOW_NANOS)).thenReturn(Optional.of(active(41L)));
        when(customers.findContact(999L)).thenReturn(Optional.empty());

        assertThat(service.getCustomerStatus(CUSTOMER).restricted()).isTrue();
        assertThatThrownBy(() -> service.getCustomerStatus(999L)).isInstanceOf(ResourceNotFoundException.class);
        verify(restrictions, never()).findActive(org.mockito.ArgumentMatchers.eq(999L), any());
        verifyNoInteractions(locks, audit);
    }

    @Test
    void viewsAreReadOnly() throws NoSuchMethodException {
        assertThat(BookingRestrictionStatusService.class.getMethod("getMyStatus")
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(BookingRestrictionStatusService.class.getMethod("getCustomerStatus", Long.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private static BookingRestriction active(long id) {
        BookingRestriction restriction = BookingRestriction.start(CUSTOMER, STARTS, ENDS, 3);
        ReflectionTestUtils.setField(restriction, "id", id);
        return restriction;
    }
}
