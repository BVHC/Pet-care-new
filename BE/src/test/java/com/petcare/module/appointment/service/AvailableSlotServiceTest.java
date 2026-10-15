package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.SlotResponse;
import com.petcare.module.appointment.dto.SlotResponse.SlotStatus;
import com.petcare.module.appointment.mapper.SlotMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.SlotBookedCount;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AvailableSlotService} (docs/adr/0042): thứ tự kiểm, mỗi nhánh lỗi dừng trước lời gọi kế tiếp, khách không qua
 * {@code BranchScope}, cửa sổ BR-LH-04, đếm + quota từng khung, ranh giới transaction.
 */
class AvailableSlotServiceTest {

    private static final long BRANCH = 3L;
    private static final long OTHER_BRANCH = 4L;
    private static final long SERVICE = 12L;
    /** 2026-10-15 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate IN_WINDOW = TODAY.plusDays(2);
    private static final List<TimeRange> MORNING = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(10, 0)));

    private final BranchScope scope = mock(BranchScope.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AvailableSlotService service = new AvailableSlotService(scope, branches, catalog, configs,
            appointments, Mappers.getMapper(SlotMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(configs.getInt(ConfigKey.APPOINTMENT_MIN_LEAD_HOURS)).thenReturn(24);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_ADVANCE_DAYS)).thenReturn(30);
    }

    // ---------------------------------------------------------------- đường thành công

    @Test
    void customerGetsEverySlotWithQuotaBookedAndStatusInCallOrder() {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, IN_WINDOW)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "MEDICAL", IN_WINDOW))
                .thenReturn(List.of(count(LocalTime.of(8, 30), 2), count(LocalTime.of(9, 30), 4)));
        quota(LocalTime.of(8, 0), 1);
        quota(LocalTime.of(8, 30), 2);
        quota(LocalTime.of(9, 0), 0);
        quota(LocalTime.of(9, 30), 3);

        List<SlotResponse> slots = service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW);

        assertThat(slots).containsExactly(
                slot(8, 0, 1, 0, SlotStatus.AVAILABLE),
                slot(8, 30, 2, 2, SlotStatus.FULL),
                slot(9, 0, 0, 0, SlotStatus.FULL),
                slot(9, 30, 3, 4, SlotStatus.FULL));
        InOrder order = inOrder(branches, catalog, appointments);
        order.verify(branches).findBranch(BRANCH);
        order.verify(catalog).findService(SERVICE);
        order.verify(branches).isServiceEnabled(BRANCH, SERVICE);
        order.verify(branches).openingRangesOn(BRANCH, IN_WINDOW);
        order.verify(appointments).countActiveBySlot(BRANCH, "MEDICAL", IN_WINDOW);
        order.verify(branches).quotaFor(BRANCH, ServiceGroup.MEDICAL, IN_WINDOW, LocalTime.of(8, 0));
        verify(scope, never()).check(any());
    }

    @Test
    void groomingCountsByItsOwnGroup() {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.GROOMING);
        when(branches.openingRangesOn(BRANCH, IN_WINDOW)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "GROOMING", IN_WINDOW)).thenReturn(List.of());
        when(branches.quotaFor(eq(BRANCH), eq(ServiceGroup.GROOMING), eq(IN_WINDOW), any())).thenReturn(1);

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW)).hasSize(4)
                .allMatch(s -> s.status() == SlotStatus.AVAILABLE && s.booked() == 0);
        verify(appointments).countActiveBySlot(BRANCH, "GROOMING", IN_WINDOW);
    }

    @Test
    void customerSeesOnlySlotsAtLeastTwentyFourHoursAhead() {
        asCustomer();
        LocalDate tomorrow = TODAY.plusDays(1);
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, tomorrow)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "MEDICAL", tomorrow)).thenReturn(List.of());
        when(branches.quotaFor(eq(BRANCH), eq(ServiceGroup.MEDICAL), eq(tomorrow), any())).thenReturn(1);

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, tomorrow)).extracting(SlotResponse::slotStart)
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
        verify(branches, never()).quotaFor(BRANCH, ServiceGroup.MEDICAL, tomorrow, LocalTime.of(8, 0));
    }

    @Test
    void receptionistSeesOnlySlotsNotYetStartedToday() {
        asReceptionist();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, TODAY)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "MEDICAL", TODAY)).thenReturn(List.of());
        when(branches.quotaFor(eq(BRANCH), eq(ServiceGroup.MEDICAL), eq(TODAY), any())).thenReturn(1);

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, TODAY)).extracting(SlotResponse::slotStart)
                .containsExactly(LocalTime.of(9, 30));
        verify(scope).check(BRANCH);
    }

    @Test
    void receptionistHasNoFarLimitButCustomerDoes() {
        LocalDate far = TODAY.plusDays(31);
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, far)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "MEDICAL", far)).thenReturn(List.of());
        when(branches.quotaFor(eq(BRANCH), eq(ServiceGroup.MEDICAL), eq(far), any())).thenReturn(1);

        asReceptionist();
        assertThat(service.listAvailableSlots(BRANCH, SERVICE, far)).hasSize(4);

        asCustomer();
        assertThat(service.listAvailableSlots(BRANCH, SERVICE, far)).isEmpty();
    }

    // ---------------------------------------------------------------- rỗng, không đếm

    @ParameterizedTest
    @ValueSource(ints = {-1, 31})
    void customerDateOutsideWindowIsEmptyWithoutReadingSchedule(int days) {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, TODAY.plusDays(days))).isEmpty();

        verify(branches, never()).openingRangesOn(anyLong(), any());
        verifyNoInteractions(appointments);
        verify(branches, never()).quotaFor(anyLong(), any(), any(), any());
    }

    @Test
    void staffPastDateIsEmpty() {
        asReceptionist();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, TODAY.minusDays(1))).isEmpty();

        verify(branches, never()).openingRangesOn(anyLong(), any());
        verifyNoInteractions(appointments);
    }

    @Test
    void closedDayOrHolidayIsEmptyWithoutCountingOrQuota() {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, IN_WINDOW)).thenReturn(List.of());

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW)).isEmpty();

        verifyNoInteractions(appointments);
        verify(branches, never()).quotaFor(anyLong(), any(), any(), any());
    }

    @Test
    void everySlotFilteredOutIsEmptyWithoutCounting() {
        asReceptionist();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, TODAY))
                .thenReturn(List.of(new TimeRange(LocalTime.of(7, 0), LocalTime.of(9, 0))));

        assertThat(service.listAvailableSlots(BRANCH, SERVICE, TODAY)).isEmpty();

        verifyNoInteractions(appointments);
    }

    // ---------------------------------------------------------------- lỗi, đúng thứ tự

    @Test
    void receptionistOfAnotherBranchIsDeniedBeforeAnyRead() {
        asReceptionist();
        doThrow(new AccessDeniedScopeException("branch:" + OTHER_BRANCH, "branch:" + BRANCH))
                .when(scope).check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.listAvailableSlots(OTHER_BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(branches, catalog, configs, appointments);
    }

    @Test
    void unexpectedRoleFailsClosedWithoutReading() {
        when(principal.role()).thenReturn("VET");

        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(IllegalStateException.class);

        verify(scope, never()).check(any());
        verifyNoInteractions(branches, catalog, configs, appointments);
    }

    @Test
    void missingBranchIs404ForBothRoles() {
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());
        for (String role : List.of("CUSTOMER", "RECEPTIONIST")) {
            when(principal.role()).thenReturn(role);
            assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Không tìm thấy chi nhánh #" + BRANCH);
        }
        verifyNoInteractions(catalog, appointments);
    }

    @Test
    void draftBranchLooksMissingToCustomers() {
        asCustomer();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());
        String missing = messageOf(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));

        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(missing);
        verifyNoInteractions(catalog, appointments);
        verify(branches, never()).isServiceEnabled(anyLong(), anyLong());
    }

    @Test
    void missingServiceIs404BeforeDraftCheck() {
        asReceptionist();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy dịch vụ #" + SERVICE);
        verify(branches, never()).isServiceEnabled(anyLong(), anyLong());
        verifyNoInteractions(appointments);
    }

    @Test
    void receptionistOfDraftBranchGetsBrLh01() {
        asReceptionist();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(serviceInfo(ServiceGroup.MEDICAL, true)));

        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Chi nhánh chưa nhận đặt lịch (BR-LH-01)");
        verify(branches, never()).isServiceEnabled(anyLong(), anyLong());
        verify(branches, never()).openingRangesOn(anyLong(), any());
        verifyNoInteractions(appointments);
    }

    @Test
    void inactiveServiceIsBrLh01() {
        asCustomer();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(serviceInfo(ServiceGroup.MEDICAL, false)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(true);

        assertNotBookable();
    }

    @Test
    void boardingServiceIsBrLh01AndNeverReachesQuota() {
        asCustomer();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(serviceInfo(ServiceGroup.BOARDING, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(true);

        assertNotBookable();
    }

    @Test
    void serviceDisabledAtBranchIsBrLh01() {
        asReceptionist();
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(serviceInfo(ServiceGroup.GROOMING, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(false);

        assertNotBookable();
    }

    @Test
    void failureFromBranchModulePropagatesUntouched() {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        IllegalArgumentException broken = new IllegalArgumentException("Khoảng giờ mở cửa không hợp lệ");
        when(branches.openingRangesOn(BRANCH, IN_WINDOW)).thenThrow(broken);

        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW)).isSameAs(broken);
        verifyNoInteractions(appointments);
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void oneReadOnlyRepeatableReadTransactionWithoutRollbackRules() throws NoSuchMethodException {
        Method method = AvailableSlotService.class.getMethod("listAvailableSlots", Long.class, Long.class,
                LocalDate.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.rollbackFor()).isEmpty();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(AvailableSlotService.class.getAnnotation(Transactional.class)).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private void assertNotBookable() {
        assertThatThrownBy(() -> service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)");
        verify(branches, never()).openingRangesOn(anyLong(), any());
        verify(branches, never()).quotaFor(anyLong(), any(), any(), any());
        verifyNoInteractions(appointments);
        verify(configs, never()).getInt(any());
    }

    private void asCustomer() {
        when(principal.role()).thenReturn("CUSTOMER");
    }

    private void asReceptionist() {
        when(principal.role()).thenReturn("RECEPTIONIST");
    }

    private void givenBookable(BranchStatus status, ServiceGroup group) {
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(status)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(serviceInfo(group, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(true);
    }

    private void quota(LocalTime start, int quota) {
        when(branches.quotaFor(BRANCH, ServiceGroup.MEDICAL, IN_WINDOW, start)).thenReturn(quota);
    }

    private static BranchSummary branch(BranchStatus status) {
        return new BranchSummary(BRANCH, "Chi nhánh", "Địa chỉ", "0280000000", status, false);
    }

    private static ServiceInfo serviceInfo(ServiceGroup group, boolean active) {
        return new ServiceInfo(SERVICE, "Dịch vụ", group, group == ServiceGroup.MEDICAL ? MedicalType.EXAM : null,
                150000, active);
    }

    private static SlotResponse slot(int hour, int minute, int quota, int booked, SlotStatus status) {
        LocalTime start = LocalTime.of(hour, minute);
        return new SlotResponse(start, start.plusMinutes(30), quota, booked, status);
    }

    private static SlotBookedCount count(LocalTime start, int booked) {
        return new SlotBookedCount() {
            @Override
            public int getSecondOfDay() {
                return start.toSecondOfDay();
            }

            @Override
            public int getBooked() {
                return booked;
            }
        };
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("không ném lỗi");
    }

    @Test
    void noOtherCollaboratorIsTouchedOnSuccess() {
        asCustomer();
        givenBookable(BranchStatus.ACTIVE, ServiceGroup.MEDICAL);
        when(branches.openingRangesOn(BRANCH, IN_WINDOW)).thenReturn(MORNING);
        when(appointments.countActiveBySlot(BRANCH, "MEDICAL", IN_WINDOW)).thenReturn(List.of());
        when(branches.quotaFor(eq(BRANCH), eq(ServiceGroup.MEDICAL), eq(IN_WINDOW), any())).thenReturn(1);

        service.listAvailableSlots(BRANCH, SERVICE, IN_WINDOW);

        verify(appointments).countActiveBySlot(BRANCH, "MEDICAL", IN_WINDOW);
        verifyNoMoreInteractions(appointments);
    }
}
