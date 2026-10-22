package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.api.AppointmentApi.CheckInInfo;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.CheckInRef;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
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
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.dto.CreateVisitRequest;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.fsm.VisitTransitionHandler;
import com.petcare.module.visit.mapper.VisitMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link VisitCheckInService} (Visit#1, docs/adr/0059): thứ tự gọi (khóa thú trước, 404 trước 400, 400 trước
 * {@code checkIn}, mọi guard trước câu ghi đầu tiên), mỗi nhánh lỗi không sinh mã / không ghi / không mở Order, cặp thứ
 * tự lỗi liền kề, giá trị ghi xuống và response.
 */
class VisitCheckInServiceTest {

    private static final long ACCOUNT = 70L;
    private static final long CUSTOMER = 88L;
    private static final long PET = 12L;
    private static final long BRANCH = 3L;
    private static final long SERVICE = 7L;
    private static final long APPOINTMENT = 501L;
    private static final long VISIT_ID = 9001L;
    private static final long ORDER_ID = 7001L;
    /** 2026-10-15 09:00 giờ Việt Nam, có phần lẻ dưới µs để kiểm cắt về µs. */
    private static final Instant RAW_NOW = Instant.parse("2026-10-15T02:00:00.123456789Z");
    private static final Instant NOW = RAW_NOW.truncatedTo(ChronoUnit.MICROS);
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.ofInstant(NOW, TimeConfig.BUSINESS_ZONE);

    private final BranchScope scope = mock(BranchScope.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final AppointmentQueryApi appointmentQueries = mock(AppointmentQueryApi.class);
    private final AppointmentApi appointments = mock(AppointmentApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final VisitCodeGenerator codes = mock(VisitCodeGenerator.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final Clock clock = mock(Clock.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitCheckInService service = new VisitCheckInService(scope, pets, appointmentQueries, appointments,
            catalog, branches, customers, visitOrders, configs, codes, visits, new VisitTransitionHandler(),
            Mappers.getMapper(VisitMapper.class), clock);

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(scope.resolve(null)).thenReturn(BRANCH);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(clock.instant()).thenReturn(RAW_NOW);
        when(configs.getInt(ConfigKey.VISIT_LATE_PRIORITY_MINUTES)).thenReturn(15);
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(CUSTOMER, null)));
        when(appointmentQueries.findCheckInRef(APPOINTMENT))
                .thenReturn(Optional.of(new CheckInRef(APPOINTMENT, BRANCH, PET, SERVICE)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, true)));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE, false)));
        when(branches.isOpenAt(BRANCH, LOCAL_NOW)).thenReturn(true);
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(true);
        when(appointments.checkIn(APPOINTMENT, BRANCH, NOW)).thenReturn(checkInInfo(CUSTOMER, NOW));
        when(codes.nextCode()).thenReturn("TN-2610-00042");
        when(visits.saveAndFlush(any(Visit.class))).thenAnswer(invocation -> {
            Visit visit = invocation.getArgument(0);
            ReflectionTestUtils.setField(visit, "id", VISIT_ID);
            return visit;
        });
        when(visitOrders.openForVisit(VISIT_ID, BRANCH, CUSTOMER, SERVICE, null, ACCOUNT))
                .thenReturn(new LineRef(ORDER_ID, 7101L));
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(
                new CustomerContact(CUSTOMER, "Nguyễn Văn An", "0901234567", "an@example.com", null, false)));
    }

    // ---------------------------------------------------------------- đường thành công

    @Test
    void appointmentCheckInRunsInLockReadGuardWriteOrderAndPersistsEverything() {
        VisitResponse response = service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, 99L, null));

        InOrder order = inOrder(pets, appointmentQueries, catalog, clock, branches, appointments, configs, codes,
                visits, visitOrders, customers);
        order.verify(pets).findPetForShare(PET);
        order.verify(appointmentQueries).findCheckInRef(APPOINTMENT);
        order.verify(catalog).findService(SERVICE);
        order.verify(clock).instant();
        order.verify(branches).findBranch(BRANCH);
        order.verify(branches).isOpenAt(BRANCH, LOCAL_NOW);
        order.verify(appointments).checkIn(APPOINTMENT, BRANCH, NOW);
        order.verify(configs).getInt(ConfigKey.VISIT_LATE_PRIORITY_MINUTES);
        order.verify(codes).nextCode();
        order.verify(visits).saveAndFlush(any(Visit.class));
        order.verify(visitOrders).openForVisit(VISIT_ID, BRANCH, CUSTOMER, SERVICE, null, ACCOUNT);
        order.verify(customers).findContact(CUSTOMER);
        verify(catalog, never()).findService(99L);
        verify(branches, never()).isServiceEnabled(anyLong(), anyLong());

        Visit saved = savedVisit();
        assertThat(saved.getCode()).isEqualTo("TN-2610-00042");
        assertThat(saved.getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(saved.getPetId()).isEqualTo(PET);
        assertThat(saved.getAppointmentId()).isEqualTo(APPOINTMENT);
        assertThat(saved.getServiceGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(saved.getStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(saved.getPriorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(saved.getQueueSortKey()).isEqualTo(NOW);
        assertThat(saved.getAssigneeId()).isNull();
        assertThat(saved.isNeedsReassign()).isFalse();
        assertThat(saved.getCheckedInBy()).isEqualTo(ACCOUNT);
        assertThat(saved.getCheckedInAt()).as("cắt về µs").isEqualTo(NOW);

        assertThat(response.visitId()).isEqualTo(VISIT_ID);
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.petName()).isEqualTo("Milu");
        assertThat(response.species()).isEqualTo(Species.CAT);
        assertThat(response.assigneeName()).isNull();
    }

    @Test
    void walkInChecksTheBranchToggleAndNeverTouchesAppointments() {
        VisitResponse response = service.checkInVisit(new CreateVisitRequest(PET, null, SERVICE, false));

        verify(branches).isServiceEnabled(BRANCH, SERVICE);
        verify(appointmentQueries, never()).findCheckInRef(any());
        verify(appointments, never()).checkIn(any(), any(), any());
        Visit saved = savedVisit();
        assertThat(saved.getAppointmentId()).isNull();
        assertThat(saved.getPriorityClass()).isEqualTo(PriorityClass.WALK_IN);
        assertThat(saved.getQueueSortKey()).isEqualTo(NOW);
        assertThat(response.appointmentId()).isNull();
    }

    @Test
    void serviceGroupOfAnAppointmentComesFromTheAppointment() {
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.GROOMING, true)));
        when(appointments.checkIn(APPOINTMENT, BRANCH, NOW)).thenReturn(new CheckInInfo(APPOINTMENT, CUSTOMER, PET,
                SERVICE, ServiceGroup.GROOMING, NOW));

        service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null));

        assertThat(savedVisit().getServiceGroup()).isEqualTo(ServiceGroup.GROOMING);
    }

    @Test
    void emergencyInOpeningHoursWithAnAppointmentIsEmergencyAtCheckInTime() {
        when(appointments.checkIn(APPOINTMENT, BRANCH, NOW))
                .thenReturn(checkInInfo(CUSTOMER, NOW.plus(Duration.ofMinutes(20))));

        service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, true));

        assertThat(savedVisit().getPriorityClass()).isEqualTo(PriorityClass.EMERGENCY);
        assertThat(savedVisit().getQueueSortKey()).isEqualTo(NOW);
    }

    @Test
    void lateBeyondThePriorityWindowIsQueuedAsWalkIn() {
        when(appointments.checkIn(APPOINTMENT, BRANCH, NOW))
                .thenReturn(checkInInfo(CUSTOMER, NOW.minus(Duration.ofMinutes(16))));

        service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null));

        assertThat(savedVisit().getPriorityClass()).isEqualTo(PriorityClass.WALK_IN);
        assertThat(savedVisit().getQueueSortKey()).isEqualTo(NOW);
    }

    @Test
    void afterHoursEmergencyAtAFlaggedBranchIsAccepted() {
        when(branches.isOpenAt(BRANCH, LOCAL_NOW)).thenReturn(false);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE, true)));

        service.checkInVisit(new CreateVisitRequest(PET, null, SERVICE, true));

        assertThat(savedVisit().getPriorityClass()).isEqualTo(PriorityClass.EMERGENCY);
    }

    @Test
    void appointmentServiceDisabledAtTheBranchAfterBookingIsStillCheckedIn() {
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(false);

        service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null));

        verify(visits).saveAndFlush(any(Visit.class));
        verify(branches, never()).isServiceEnabled(anyLong(), anyLong());
    }

    // ---------------------------------------------------------------- 404

    @Test
    void missingPetIs404AndNothingElseHappens() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy thú cưng #12");
        verify(appointmentQueries, never()).findCheckInRef(any());
        assertNothingWritten();
    }

    @Test
    void missingAppointmentIs404BeforeAnyRuleEvenOnADraftBranch() {
        when(appointmentQueries.findCheckInRef(APPOINTMENT)).thenReturn(Optional.empty());
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT, false)));

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy lịch hẹn #501");
        assertNothingWritten();
    }

    @Test
    void missingWalkInServiceIs404BeforeTheDeceasedRule() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(CUSTOMER, LocalDate.of(2026, 10, 1))));
        when(catalog.findService(SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, null, SERVICE, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy dịch vụ #7");
        assertNothingWritten();
    }

    @Test
    void appointmentServiceMissingIsCorruptData() {
        when(catalog.findService(SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertNothingWritten();
    }

    // ---------------------------------------------------------------- 400, theo đúng thứ tự

    @Test
    void appointmentOfAnotherBranchIsRejectedBeforeRevealingItsPetOrService() {
        when(appointmentQueries.findCheckInRef(APPOINTMENT))
                .thenReturn(Optional.of(new CheckInRef(APPOINTMENT, 99L, 13L, SERVICE)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, false)));

        assertRule(new CreateVisitRequest(PET, APPOINTMENT, null, null), "BR-TN-02",
                "Lịch hẹn thuộc chi nhánh khác (BR-TN-02)");
        verify(catalog, never()).findService(any());
    }

    @Test
    void walkInWithoutServiceIsTheFirstRuleEvenForADeceasedPet() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(CUSTOMER, LocalDate.of(2026, 10, 1))));

        assertRule(new CreateVisitRequest(PET, null, null, null), "BR-TN-01",
                "Khách không hẹn trước phải chọn dịch vụ (BR-TN-01)");
    }

    @Test
    void petNotOfTheAppointmentBeatsDeceased() {
        when(appointmentQueries.findCheckInRef(APPOINTMENT))
                .thenReturn(Optional.of(new CheckInRef(APPOINTMENT, BRANCH, 13L, SERVICE)));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(CUSTOMER, LocalDate.of(2026, 10, 1))));

        assertRule(new CreateVisitRequest(PET, APPOINTMENT, null, null), "BR-TN-01",
                "Thú cưng không khớp lịch hẹn (BR-TN-01)");
    }

    @Test
    void deceasedPetBeatsADraftBranch() {
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(CUSTOMER, LocalDate.of(2026, 10, 1))));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT, false)));

        assertRule(new CreateVisitRequest(PET, null, SERVICE, null), "BR-TN-01",
                "Thú cưng đã mất, không tiếp nhận được (BR-TN-01)");
    }

    @Test
    void draftBranchBeatsOpeningHours() {
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.DRAFT, false)));
        when(branches.isOpenAt(BRANCH, LOCAL_NOW)).thenReturn(false);

        assertRule(new CreateVisitRequest(PET, null, SERVICE, null), "BR-CN-01",
                "Chi nhánh chưa hoạt động, chưa tiếp nhận được (BR-CN-01)");
    }

    @Test
    void afterHoursWithoutTheFlagBeatsAStoppedService() {
        when(branches.isOpenAt(BRANCH, LOCAL_NOW)).thenReturn(false);
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, false)));

        assertRule(new CreateVisitRequest(PET, null, SERVICE, true), "BR-CN-05",
                "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)");
    }

    @Test
    void afterHoursAtAFlaggedBranchNeedsTheEmergencyMark() {
        when(branches.isOpenAt(BRANCH, LOCAL_NOW)).thenReturn(false);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(branch(BranchStatus.ACTIVE, true)));

        assertRule(new CreateVisitRequest(PET, APPOINTMENT, null, false), "BR-CN-05",
                "Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05)");
    }

    @Test
    void walkInServiceMustBeActiveVisitGroupAndEnabledAtTheBranch() {
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, false)));
        assertRule(new CreateVisitRequest(PET, null, SERVICE, null), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");

        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.BOARDING, true)));
        assertRule(new CreateVisitRequest(PET, null, SERVICE, null), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");

        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.GROOMING, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(false);
        assertRule(new CreateVisitRequest(PET, null, SERVICE, null), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");
    }

    @Test
    void stoppedAppointmentServiceIs400BeforeTheAppointmentIsTouched() {
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, false)));

        assertRule(new CreateVisitRequest(PET, APPOINTMENT, null, null), "BR-LH-01",
                "Dịch vụ của lịch hẹn đã ngừng kinh doanh (BR-LH-01)");
    }

    // ---------------------------------------------------------------- lỗi từ checkIn và sau checkIn

    @Test
    void conflictFromTheAppointmentPropagatesWithoutCodeOrInsert() {
        doThrow(new InvalidStateTransitionException("lịch hẹn", "NO_SHOW", "CHECKED_IN"))
                .when(appointments).checkIn(APPOINTMENT, BRANCH, NOW);

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(codes, never()).nextCode();
        verify(visits, never()).saveAndFlush(any());
        verify(visitOrders, never()).openForVisit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ownerMismatchAfterCheckInIsCorruptDataAndStopsBeforeTheInsert() {
        when(appointments.checkIn(APPOINTMENT, BRANCH, NOW)).thenReturn(checkInInfo(77L, NOW));

        assertThatThrownBy(() -> service.checkInVisit(new CreateVisitRequest(PET, APPOINTMENT, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(codes, never()).nextCode();
        verify(visits, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- hỗ trợ

    /** Lỗi rule ở bước 5–10: chưa gọi checkIn, không sinh mã, không ghi, không mở Order. */
    private void assertRule(CreateVisitRequest request, String ruleId, String message) {
        assertThatThrownBy(() -> service.checkInVisit(request))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo(ruleId))
                .hasMessage(message);
        assertNothingWritten();
    }

    private void assertNothingWritten() {
        verify(appointments, never()).checkIn(any(), any(), any());
        verify(codes, never()).nextCode();
        verify(visits, never()).saveAndFlush(any());
        verify(visitOrders, never()).openForVisit(any(), any(), any(), any(), any(), any());
    }

    private Visit savedVisit() {
        ArgumentCaptor<Visit> captor = ArgumentCaptor.forClass(Visit.class);
        verify(visits).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static PetSummary pet(long customerId, LocalDate deceasedOn) {
        return new PetSummary(PET, customerId, "Milu", Species.CAT, null, deceasedOn, null);
    }

    private static ServiceInfo service(ServiceGroup group, boolean active) {
        return new ServiceInfo(SERVICE, "Khám tổng quát", group,
                group == ServiceGroup.MEDICAL ? MedicalType.EXAM : null, 150_000, active);
    }

    private static BranchSummary branch(BranchStatus status, boolean afterHoursEmergency) {
        return new BranchSummary(BRANCH, "Pet Care Quận 1", "Địa chỉ", "0280000000", status, afterHoursEmergency);
    }

    private static CheckInInfo checkInInfo(long customerId, Instant scheduledAt) {
        return new CheckInInfo(APPOINTMENT, customerId, PET, SERVICE, ServiceGroup.MEDICAL, scheduledAt);
    }
}
