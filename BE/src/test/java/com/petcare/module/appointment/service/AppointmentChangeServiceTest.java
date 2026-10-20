package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.CancelAppointmentRequest;
import com.petcare.module.appointment.dto.RescheduleAppointmentRequest;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.AccessRow;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AppointmentChangeService} (docs/adr/0045 §4, 0049): thứ tự phân quyền → khóa → {@code now} → 409 → guard → ghi,
 * mọi nhánh lỗi không ghi, mốc biên của BR-LH-06 / 07, nguồn hủy, hủy muộn gọi đánh giá BR-LH-09, ranh giới
 * transaction.
 */
class AppointmentChangeServiceTest {

    private static final long ACCOUNT = 70L;
    private static final long CUSTOMER_ID = 88L;
    private static final long APPOINTMENT = 501L;
    private static final long PET = 12L;
    private static final long BRANCH = 3L;
    private static final long SERVICE = 7L;
    /** 2026-10-15 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final LocalTime START = LocalTime.of(8, 30);
    private static final LocalTime NEW_START = LocalTime.of(9, 0);
    private static final List<TimeRange> MORNING = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(10, 0)));

    /** Projection {@link AccessRow} bằng record: tên thành phần trùng tên getter. */
    record Access(Long getCustomerId, Long getPetId, Long getBranchId) implements AccessRow {
    }

    private final BranchScope scope = mock(BranchScope.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchApi branchLocks = mock(BranchApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final BookingRestrictionApi restrictions = mock(BookingRestrictionApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentLocks locks = mock(AppointmentLocks.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final Clock clock = mock(Clock.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AppointmentChangeService service = new AppointmentChangeService(scope, customers, pets, catalog,
            branchLocks, branches, restrictions, configs, locks, appointments, new AppointmentTransitionHandler(),
            Mappers.getMapper(AppointmentMapper.class), clock);

    private Appointment appointment;

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(clock.instant()).thenReturn(NOW);
        when(configs.getInt(ConfigKey.APPOINTMENT_MIN_LEAD_HOURS)).thenReturn(24);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_ADVANCE_DAYS)).thenReturn(30);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES)).thenReturn(3);
        when(configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS)).thenReturn(12);
        when(configs.getInt(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS)).thenReturn(12);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER_ID));

        appointment = appointment(DAY, START);
        when(appointments.findAccessRow(APPOINTMENT)).thenReturn(Optional.of(new Access(CUSTOMER_ID, PET, BRANCH)));
        when(appointments.findByIdForUpdate(APPOINTMENT)).thenAnswer(invocation -> Optional.of(appointment));
        when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.ACTIVE));
        when(branches.openingRangesOn(anyLong(), any())).thenReturn(MORNING);
        when(branches.quotaFor(anyLong(), any(), any(), any())).thenReturn(2);

        when(branches.findBranch(anyLong())).thenAnswer(invocation -> Optional.of(new BranchSummary(
                invocation.getArgument(0), "Pet Care Quận 1", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false)));
        when(pets.findPets(any())).thenAnswer(invocation -> ((Collection<Long>) invocation.getArgument(0)).stream()
                .map(id -> new PetSummary(id, CUSTOMER_ID, "Mít", Species.DOG, null, null, null)).toList());
        when(customers.findContacts(any())).thenAnswer(invocation -> ((Collection<Long>) invocation.getArgument(0))
                .stream().map(id -> new CustomerContact(id, "Nguyễn Văn An", null, null, null, false)).toList());
        when(catalog.findServices(any())).thenAnswer(invocation -> ((Collection<Long>) invocation.getArgument(0))
                .stream().map(id -> new ServiceInfo(id, "Khám tổng quát", ServiceGroup.MEDICAL, MedicalType.EXAM,
                        150_000L, true)).toList());
    }

    // ================================================================ #5 đổi khung — đường thành công

    @Test
    void receptionistReschedulesWithLocksBeforeEveryReadOfTheRow() {
        asReceptionist();

        AppointmentResponse response = service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));

        InOrder order = inOrder(scope, appointments, locks, branchLocks, clock, branches);
        order.verify(appointments).findAccessRow(APPOINTMENT);
        order.verify(scope).check(BRANCH);
        order.verify(locks).lockPet(PET);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(appointments).findByIdForUpdate(APPOINTMENT);
        order.verify(clock).instant();
        order.verify(branches).openingRangesOn(BRANCH, DAY);
        order.verify(appointments).existsActiveSameSlotExcept(PET, DAY, NEW_START, APPOINTMENT);
        order.verify(appointments).existsActiveSameGroupDayExcept(PET, "MEDICAL", DAY, APPOINTMENT);
        order.verify(appointments).countActiveInSlot(BRANCH, "MEDICAL", DAY, NEW_START);
        order.verify(branches).quotaFor(BRANCH, ServiceGroup.MEDICAL, DAY, NEW_START);
        order.verify(appointments).saveAndFlush(appointment);

        assertThat(response.slotDate()).isEqualTo(DAY);
        assertThat(response.slotStart()).isEqualTo(NEW_START);
        assertThat(response.rescheduleCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(response.code()).isEqualTo("LH-261015-0501");
        assertThat(response.petName()).isEqualTo("Mít");
        assertThat(response.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.canReschedule()).isTrue();
        assertThat(response.canCancel()).isTrue();
        verify(customers, never()).findCustomerIdByAccountId(anyLong());
        verifyNoInteractions(restrictions);
    }

    @Test
    void customerReschedulesOwnAppointmentWithoutBranchScope() {
        asCustomer();

        AppointmentResponse response = service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));

        InOrder order = inOrder(customers, appointments, locks);
        order.verify(customers).findCustomerIdByAccountId(ACCOUNT);
        order.verify(appointments).findAccessRow(APPOINTMENT);
        order.verify(locks).lockPet(PET);
        verify(scope, never()).check(any());
        assertThat(response.slotStart()).isEqualTo(NEW_START);
    }

    @Test
    void rescheduleClearsTheReminderMark() throws ReflectiveOperationException {
        asReceptionist();
        set(appointment, "remindedAt", NOW.minus(Duration.ofHours(1)));

        service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));

        assertThat(appointment.getRemindedAt()).isNull();
    }

    @Test
    void lastRescheduleTurnsTheFlagOff() throws ReflectiveOperationException {
        asReceptionist();
        set(appointment, "rescheduleCount", (short) 2);

        AppointmentResponse response = service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));

        assertThat(response.rescheduleCount()).isEqualTo(3);
        assertThat(response.canReschedule()).isFalse();
        assertThat(response.canCancel()).isTrue();
    }

    @Test
    void sameSlotIsANoOpAfterTheStateCheck() {
        asReceptionist();

        AppointmentResponse response = service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, START));

        assertThat(response.rescheduleCount()).isZero();
        assertThat(response.slotStart()).isEqualTo(START);
        verify(appointments, never()).saveAndFlush(any());
        verify(appointments, never()).countActiveInSlot(anyLong(), any(), any(), any());
        verify(branches, never()).openingRangesOn(anyLong(), any());
    }

    // ================================================================ #5 — phân quyền trước khóa

    @Test
    void otherCustomersAppointmentIsTheSame404AsAMissingOneAndTakesNoLock() {
        asCustomer();
        when(appointments.findAccessRow(APPOINTMENT)).thenReturn(Optional.of(new Access(999L, PET, BRANCH)));
        when(appointments.findAccessRow(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy lịch hẹn #501");
        assertThatThrownBy(() -> service.rescheduleAppointment(404L, reschedule(DAY, NEW_START)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy lịch hẹn #404");
        verifyNoInteractions(locks, branchLocks);
        verify(appointments, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void receptionistOfAnotherBranchIsDeniedBeforeAnyLock() {
        asReceptionist();
        doThrow(new AccessDeniedScopeException("branch:" + BRANCH, "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> service.cancelAppointment(APPOINTMENT, null))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(locks, branchLocks);
        verify(appointments, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void unexpectedRoleIsAProgrammingError() {
        when(principal.role()).thenReturn("VET");

        assertThatThrownBy(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.cancelAppointment(APPOINTMENT, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(locks, branchLocks);
    }

    // ================================================================ #5 — 409 trước mọi rule (Q6)

    @Test
    void notBookedIs409BeforeAnyRule() throws ReflectiveOperationException {
        asCustomer();
        set(appointment, "rescheduleCount", (short) 3);
        appointment.cancel(AppointmentCancelSource.STAFF, null, NOW, false);

        assertThatThrownBy(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang BOOKED");
        assertNothingWritten();
        verify(branches, never()).openingRangesOn(anyLong(), any());
    }

    @Test
    void checkedInCannotBeRescheduled() throws ReflectiveOperationException {
        asReceptionist();
        set(appointment, "status", AppointmentStatus.CHECKED_IN);

        assertThatThrownBy(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang BOOKED");
        assertNothingWritten();
    }

    // ================================================================ #5 — BR-LH-06

    @Test
    void noTurnsLeftIsBrLh06() throws ReflectiveOperationException {
        asReceptionist();
        set(appointment, "rescheduleCount", (short) 3);

        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-06",
                "Lịch hẹn đã đổi đủ 3 lần, muốn đổi nữa vui lòng hủy rồi đặt lại (BR-LH-06)");
    }

    @Test
    void customerNeedsAtLeastTheLeadHoursAndExactlyThatIsAllowed() {
        asCustomer();
        // Giờ hẹn 2026-10-15 21:00 VN = now + 12 giờ đúng.
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(21, 0));

        service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));
        assertThat(appointment.getRescheduleCount()).isEqualTo((short) 1);

        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(21, 0));
        clearInvocations(appointments);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-06",
                "Chỉ đổi được lịch trước giờ hẹn ít nhất 12 giờ, vui lòng liên hệ chi nhánh (BR-LH-06)");
    }

    @Test
    void receptionistMayRescheduleUntilTheAppointmentStarts() {
        asReceptionist();
        // Giờ hẹn 2026-10-15 09:30 VN = now + 30 phút: lễ tân vẫn đổi được.
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(9, 30));
        service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START));
        assertThat(appointment.getSlotDate()).isEqualTo(DAY);

        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(9, 30));
        clearInvocations(appointments);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T02:30:00Z"));
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-06",
                "Lịch hẹn đã tới giờ, không đổi được nữa (BR-LH-06)");
    }

    // ================================================================ #5 — khung mới: BR-LH-04, 02, 05, 03

    @Test
    void customerNewSlotMustRespectTheBookingWindow() {
        asCustomer();

        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(LocalDate.of(2026, 10, 16),
                LocalTime.of(8, 30))), "BR-LH-04",
                "Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)");
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(LocalDate.of(2026, 11, 15),
                NEW_START)), "BR-LH-04",
                "Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)");
    }

    @Test
    void receptionistNewSlotMustNotHaveStarted() {
        asReceptionist();

        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(LocalDate.of(2026, 10, 15),
                LocalTime.of(9, 0))), "BR-LH-04", "Khung giờ đã bắt đầu (BR-LH-04)");
    }

    @Test
    void newSlotOffTheGridIsBrLh02() {
        asReceptionist();

        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, LocalTime.of(9, 15))),
                "BR-LH-02", "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)");
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, LocalTime.of(10, 0))),
                "BR-LH-02", "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)");
    }

    @Test
    void petLimitsExcludeThisAppointment() {
        asReceptionist();
        when(appointments.existsActiveSameSlotExcept(PET, DAY, NEW_START, APPOINTMENT)).thenReturn(true);
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-05",
                "Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)");

        when(appointments.existsActiveSameSlotExcept(PET, DAY, NEW_START, APPOINTMENT)).thenReturn(false);
        when(appointments.existsActiveSameGroupDayExcept(PET, "MEDICAL", DAY, APPOINTMENT)).thenReturn(true);
        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-05",
                "Thú cưng đã có lịch Khám/Tiêm trong ngày này (BR-LH-05)");
        verify(appointments, never()).countBookedByPet(anyLong());
    }

    @Test
    void fullNewSlotIsBrLh03() {
        asReceptionist();
        when(appointments.countActiveInSlot(BRANCH, "MEDICAL", DAY, NEW_START)).thenReturn(2);

        assertRule(() -> service.rescheduleAppointment(APPOINTMENT, reschedule(DAY, NEW_START)), "BR-LH-03",
                "Khung giờ đã hết chỗ (BR-LH-03)");
    }

    // ================================================================ #6 hủy

    @Test
    void customerCancelsEarlyWithOnlyTheRowLock() {
        asCustomer();

        AppointmentResponse response = service.cancelAppointment(APPOINTMENT, new CancelAppointmentRequest("  Bận  "));

        InOrder order = inOrder(customers, appointments, clock);
        order.verify(customers).findCustomerIdByAccountId(ACCOUNT);
        order.verify(appointments).findAccessRow(APPOINTMENT);
        order.verify(appointments).findByIdForUpdate(APPOINTMENT);
        order.verify(clock).instant();
        order.verify(appointments).saveAndFlush(appointment);
        verifyNoInteractions(locks, branchLocks, restrictions);

        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(response.cancelSource()).isEqualTo(AppointmentCancelSource.CUSTOMER);
        assertThat(response.cancelReason()).isEqualTo("Bận");
        assertThat(response.cancelledAt()).isEqualTo(NOW);
        assertThat(response.lateCancel()).isFalse();
        assertThat(response.canReschedule()).isFalse();
        assertThat(response.canCancel()).isFalse();
    }

    @Test
    void receptionistCancelSourceIsStaffAndBodyIsOptional() {
        asReceptionist();

        AppointmentResponse response = service.cancelAppointment(APPOINTMENT, null);
        assertThat(response.cancelSource()).isEqualTo(AppointmentCancelSource.STAFF);
        assertThat(response.cancelReason()).isNull();

        appointment = appointment(DAY, START);
        assertThat(service.cancelAppointment(APPOINTMENT, new CancelAppointmentRequest("   ")).cancelReason())
                .isNull();
    }

    @Test
    void exactlyTheLateCancelHoursIsNotLate() {
        asCustomer();
        // Giờ hẹn 2026-10-15 21:00 VN = now + 12 giờ đúng.
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(21, 0));

        assertThat(service.cancelAppointment(APPOINTMENT, null).lateCancel()).isFalse();
        verifyNoInteractions(restrictions);
    }

    @Test
    void underTheLateCancelHoursIsLateAndEvaluatedAfterTheFlush() {
        asCustomer();
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(21, 0));
        Instant oneMicroLater = NOW.plusNanos(1_000);
        when(clock.instant()).thenReturn(oneMicroLater);

        AppointmentResponse response = service.cancelAppointment(APPOINTMENT, null);

        assertThat(response.lateCancel()).isTrue();
        InOrder order = inOrder(appointments, restrictions);
        order.verify(appointments).saveAndFlush(appointment);
        order.verify(restrictions).evaluateAfterViolation(CUSTOMER_ID, oneMicroLater);
    }

    @Test
    void nowIsTruncatedToMicrosecondsLikeTheColumn() {
        asCustomer();
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(10, 0));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-15T02:00:00.123456789Z"));

        AppointmentResponse response = service.cancelAppointment(APPOINTMENT, null);

        Instant micros = Instant.parse("2026-10-15T02:00:00.123456Z");
        assertThat(response.cancelledAt()).isEqualTo(micros);
        verify(restrictions).evaluateAfterViolation(CUSTOMER_ID, micros);
    }

    @Test
    void atTheAppointmentStartItIsTooLate() {
        asReceptionist();
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(9, 0));

        assertRule(() -> service.cancelAppointment(APPOINTMENT, null), "BR-LH-07",
                "Đã tới giờ hẹn, không hủy được nữa (BR-LH-07)");
        verifyNoInteractions(restrictions);
    }

    @Test
    void cancelOfANonBookedAppointmentIs409EvenWhenTheFsmHasTheEdge() throws ReflectiveOperationException {
        asReceptionist();
        set(appointment, "status", AppointmentStatus.CHECKED_IN);
        assertThatThrownBy(() -> service.cancelAppointment(APPOINTMENT, null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang CANCELLED");

        appointment = appointment(LocalDate.of(2026, 10, 14), START);
        appointment.cancel(AppointmentCancelSource.CUSTOMER, null, NOW, true);
        assertThatThrownBy(() -> service.cancelAppointment(APPOINTMENT, null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang CANCELLED");
        verify(appointments, never()).saveAndFlush(any());
        verifyNoInteractions(restrictions);
    }

    @Test
    void evaluationFailureEscapesUncaught() {
        asCustomer();
        appointment = appointment(LocalDate.of(2026, 10, 15), LocalTime.of(10, 0));
        doThrow(new UnsupportedOperationException("boarding chưa cài")).when(restrictions)
                .evaluateAfterViolation(anyLong(), any());

        assertThatThrownBy(() -> service.cancelAppointment(APPOINTMENT, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ================================================================ ranh giới transaction

    @Test
    void eachCommandIsOneWriteTransactionWithoutNoRollbackFor() throws NoSuchMethodException {
        assertThat(AppointmentChangeService.class.getAnnotation(Transactional.class)).isNull();
        for (Method method : List.of(
                AppointmentChangeService.class.getMethod("rescheduleAppointment", Long.class,
                        RescheduleAppointmentRequest.class),
                AppointmentChangeService.class.getMethod("cancelAppointment", Long.class,
                        CancelAppointmentRequest.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
    }

    // ================================================================ helper

    private void asCustomer() {
        when(principal.role()).thenReturn("CUSTOMER");
    }

    private void asReceptionist() {
        when(principal.role()).thenReturn("RECEPTIONIST");
    }

    private void assertRule(Runnable call, String ruleId, String message) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(message)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getRuleId()).isEqualTo(ruleId));
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        verify(appointments, never()).saveAndFlush(any());
        verifyNoInteractions(restrictions);
    }

    private static RescheduleAppointmentRequest reschedule(LocalDate date, LocalTime start) {
        return new RescheduleAppointmentRequest(date, start);
    }

    private static Appointment appointment(LocalDate date, LocalTime start) {
        Appointment appointment = Appointment.book("LH-261015-0501", CUSTOMER_ID, PET, BRANCH, SERVICE,
                ServiceGroup.MEDICAL, date, start, AppointmentChannel.ONLINE, ACCOUNT, null);
        try {
            set(appointment, "id", APPOINTMENT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return appointment;
    }

    private static void set(Appointment appointment, String field, Object value) throws ReflectiveOperationException {
        Field declared = Appointment.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(appointment, value);
    }
}
