package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.boarding.dto.CareLogRequest;
import com.petcare.module.boarding.dto.UpdateCareLogRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.entity.CareLog;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.CareLogAddendumRepository;
import com.petcare.module.boarding.repository.CareLogRepository;
import com.petcare.module.boarding.repository.CareLogRepository.LogKeys;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class CareLogServiceTest {

    private static final long BOOKING = 88L;
    private static final long LOG = 31L;
    private static final long ACTOR = 3L;

    private BoardingBookingRepository bookings;
    private CareLogRepository careLogs;
    private CareLogAbnormalNotices notices;
    private CareLogService service;
    private BoardingBooking booking;
    private CareLog existing;

    @BeforeEach
    void setUp() {
        BranchScope scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACTOR);
        when(scope.current()).thenReturn(principal);
        BookingKeys keys = mock(BookingKeys.class);
        when(keys.getBranchId()).thenReturn(7L);
        bookings = mock(BoardingBookingRepository.class);
        when(bookings.findKeys(BOOKING)).thenReturn(Optional.of(keys));
        when(bookings.lockStatusForShare(BOOKING)).thenReturn(Optional.of("CHECKED_IN"));
        booking = BoardingBooking.book("LT-1", 14L, 21L, 7L, 5L, LocalDate.parse("2009-06-09"),
                LocalDate.parse("2009-06-12"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "actualCheckInAt", Instant.parse("2009-06-09T03:00:00Z"));
        when(bookings.findById(BOOKING)).thenReturn(Optional.of(booking));
        careLogs = mock(CareLogRepository.class);
        when(careLogs.saveAndFlush(any())).thenAnswer(i -> {
            CareLog saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", LOG);
            ReflectionTestUtils.setField(saved, "createdAt", Instant.parse("2009-06-10T03:00:00Z"));
            return saved;
        });
        existing = CareLog.record(BOOKING, LocalDate.parse("2009-06-10"), "Ăn", null, null, null, null, List.of(),
                false, ACTOR);
        ReflectionTestUtils.setField(existing, "id", LOG);
        ReflectionTestUtils.setField(existing, "createdAt", Instant.parse("2009-06-10T02:00:00Z"));
        LogKeys logKeys = mock(LogKeys.class);
        when(logKeys.getBookingId()).thenReturn(BOOKING);
        when(logKeys.getRecordedBy()).thenReturn(ACTOR);
        when(careLogs.findKeys(LOG)).thenReturn(Optional.of(logKeys));
        when(careLogs.findByIdForUpdate(LOG)).thenReturn(Optional.of(existing));
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.CARE_LOG_MAX_PHOTOS)).thenReturn(5);
        when(configs.getInt(ConfigKey.CARE_LOG_EDIT_WINDOW_MINUTES)).thenReturn(60);
        StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
        notices = mock(CareLogAbnormalNotices.class);
        Clock clock = Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC);   // sau created_at 1 giờ
        service = new CareLogService(scope, mock(CustomerQueryApi.class), staff, configs, bookings, careLogs,
                mock(CareLogAddendumRepository.class), notices, clock);
    }

    private CareLogRequest request(boolean abnormal, List<String> photos) {
        return new CareLogRequest(null, "Ăn hết", null, null, null, null, photos, abnormal);
    }

    @Test
    void abnormalCreateSharesLocksBookingThenInsertsThenEnqueues() {
        service.create(BOOKING, request(true, List.of()));

        InOrder order = inOrder(bookings, careLogs, notices);
        order.verify(bookings).lockStatusForShare(BOOKING);
        order.verify(careLogs).saveAndFlush(any());
        order.verify(notices).enqueue(any(), any());
    }

    @Test
    void normalCreateNeverEnqueues() {
        service.create(BOOKING, request(false, List.of()));

        verify(notices, never()).enqueue(any(), any());
    }

    @Test
    void bookingNotInStayIs409AndWritesNothing() {
        when(bookings.lockStatusForShare(BOOKING)).thenReturn(Optional.of("CHECKED_OUT"));

        assertThatThrownBy(() -> service.create(BOOKING, request(true, List.of())))
                .isInstanceOf(BoardingConflictException.class);

        verify(careLogs, never()).saveAndFlush(any());
        verify(notices, never()).enqueue(any(), any());
    }

    @Test
    void tooManyPhotosIsBrLt11AndWritesNothing() {
        List<String> six = List.of("https://a/1", "https://a/2", "https://a/3", "https://a/4", "https://a/5",
                "https://a/6");

        assertThatThrownBy(() -> service.create(BOOKING, request(false, six))).isInstanceOfSatisfying(
                BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-11"));

        verify(careLogs, never()).saveAndFlush(any());
    }

    @Test
    void editExactlyAtTheWindowEndIs409AndWritesNothing() {
        // created_at 02:00Z + 60 phút = 03:00Z = đồng hồ: hết hạn (so sánh loại trừ)
        assertThatThrownBy(() -> service.update(LOG, new UpdateCareLogRequest("x", null, null, null, null, null)))
                .isInstanceOf(BoardingConflictException.class).hasMessageEndingWith("(BR-LT-11)");

        verify(careLogs, never()).saveAndFlush(any());
    }
}
