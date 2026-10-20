package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.dto.BookAppointmentRequest;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
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
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AppointmentBookingService} (docs/adr/0045 §4, 0047): thứ tự gọi (phạm vi → khóa thú → thú → dịch vụ → khóa chi
 * nhánh → {@code now} → guard → mã → INSERT), mọi nhánh lỗi không sinh mã và không lưu, thứ tự ưu tiên giữa các guard,
 * khách / lễ tân, ranh giới transaction.
 */
class AppointmentBookingServiceTest {

    private static final long ACCOUNT = 70L;
    private static final long CUSTOMER_ID = 88L;
    private static final long OTHER_CUSTOMER = 89L;
    private static final long PET = 12L;
    private static final long BRANCH = 3L;
    private static final long SERVICE = 7L;
    /** 2026-10-15 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final LocalTime START = LocalTime.of(8, 30);
    private static final List<TimeRange> MORNING = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(10, 0)));

    private final BranchScope scope = mock(BranchScope.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchApi branchLocks = mock(BranchApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final BookingRestrictionApi restrictions = mock(BookingRestrictionApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentLocks locks = mock(AppointmentLocks.class);
    private final AppointmentCodeGenerator codes = mock(AppointmentCodeGenerator.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final Clock clock = mock(Clock.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AppointmentBookingService service = new AppointmentBookingService(scope, customers, pets, catalog,
            branchLocks, branches, restrictions, configs, locks, codes, appointments,
            new AppointmentTransitionHandler(), Mappers.getMapper(AppointmentMapper.class), clock);

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(clock.instant()).thenReturn(NOW);
        when(configs.getInt(ConfigKey.APPOINTMENT_MIN_LEAD_HOURS)).thenReturn(24);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_ADVANCE_DAYS)).thenReturn(30);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_BOOKED_PER_PET)).thenReturn(2);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES)).thenReturn(3);
        when(configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS)).thenReturn(12);
        when(codes.nextCode()).thenReturn("LH-261015-0042");
        when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Pet Care Quận 1", "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false)));
    }

    // ---------------------------------------------------------------- đường thành công

    @Test
    void customerBooksOnlineInCallOrderWithLocksBeforeEveryBranchRead() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);

        AppointmentResponse response = service.bookAppointment(request(DAY, START, "  Bé hay sợ  "));

        InOrder order = inOrder(customers, locks, pets, catalog, branchLocks, clock, branches, restrictions,
                appointments, codes);
        order.verify(customers).findCustomerIdByAccountId(ACCOUNT);
        order.verify(locks).lockPet(PET);
        order.verify(pets).findPet(PET);
        order.verify(catalog).findService(SERVICE);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(clock).instant();
        order.verify(branches).isServiceEnabled(BRANCH, SERVICE);
        order.verify(customers).findContact(CUSTOMER_ID);
        order.verify(restrictions).isRestricted(CUSTOMER_ID, NOW);
        order.verify(branches).openingRangesOn(BRANCH, DAY);
        order.verify(appointments).countBookedByPet(PET);
        order.verify(appointments).existsActiveSameSlot(PET, DAY, START);
        order.verify(appointments).existsActiveSameGroupDay(PET, "MEDICAL", DAY);
        order.verify(appointments).countActiveInSlot(BRANCH, "MEDICAL", DAY, START);
        order.verify(branches).quotaFor(BRANCH, ServiceGroup.MEDICAL, DAY, START);
        order.verify(codes).nextCode();
        order.verify(appointments).saveAndFlush(any(Appointment.class));
        order.verify(branches).findBranch(BRANCH);
        verify(scope, never()).check(any());

        Appointment saved = savedAppointment();
        assertThat(saved.getCode()).isEqualTo("LH-261015-0042");
        assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(saved.getPetId()).isEqualTo(PET);
        assertThat(saved.getBranchId()).isEqualTo(BRANCH);
        assertThat(saved.getServiceId()).isEqualTo(SERVICE);
        assertThat(saved.getServiceGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(saved.getSlotDate()).isEqualTo(DAY);
        assertThat(saved.getSlotStart()).isEqualTo(START);
        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(saved.getChannel()).isEqualTo(AppointmentChannel.ONLINE);
        assertThat(saved.getBookedBy()).isEqualTo(ACCOUNT);
        assertThat(saved.getRescheduleCount()).isZero();
        assertThat(saved.isLateCancel()).isFalse();
        assertThat(saved.getNote()).isEqualTo("Bé hay sợ");

        assertThat(response.code()).isEqualTo("LH-261015-0042");
        assertThat(response.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.petName()).isEqualTo("Milu");
        assertThat(response.branchName()).isEqualTo("Pet Care Quận 1");
        assertThat(response.serviceName()).isEqualTo("Khám tổng quát");
        assertThat(response.channel()).isEqualTo(AppointmentChannel.ONLINE);
        assertThat(response.visitId()).isNull();
        assertThat(response.canReschedule()).isTrue();
        assertThat(response.canCancel()).isTrue();
    }

    @Test
    void receptionistBooksAtCounterForThePetsCurrentOwnerIgnoringCustomerOnlyRules() {
        asReceptionist();
        givenBookable(pet(OTHER_CUSTOMER, null), ServiceGroup.GROOMING, BranchStatus.ACTIVE);
        when(customers.findContact(OTHER_CUSTOMER)).thenReturn(Optional.of(contact(OTHER_CUSTOMER, true)));
        when(restrictions.isRestricted(anyLong(), any())).thenReturn(true);
        LocalDate today = LocalDate.of(2026, 10, 15);
        when(branches.openingRangesOn(BRANCH, today)).thenReturn(MORNING);

        AppointmentResponse response = service.bookAppointment(request(today, LocalTime.of(9, 30), "   "));

        InOrder order = inOrder(scope, locks, pets, branchLocks);
        order.verify(scope).check(BRANCH);
        order.verify(locks).lockPet(PET);
        order.verify(pets).findPet(PET);
        order.verify(branchLocks).lockForBooking(BRANCH);
        verify(customers, never()).findCustomerIdByAccountId(anyLong());
        verifyNoInteractions(restrictions);
        Appointment saved = savedAppointment();
        assertThat(saved.getCustomerId()).isEqualTo(OTHER_CUSTOMER);
        assertThat(saved.getChannel()).isEqualTo(AppointmentChannel.COUNTER);
        assertThat(saved.getServiceGroup()).isEqualTo(ServiceGroup.GROOMING);
        assertThat(saved.getNote()).as("toàn khoảng trắng → null").isNull();
        assertThat(response.canCancel()).isTrue();
        assertThat(response.canReschedule()).as("lễ tân: chưa tới giờ hẹn là đổi được").isTrue();
    }

    @Test
    void quotaOneBelowLimitIsAccepted() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(appointments.countActiveInSlot(BRANCH, "MEDICAL", DAY, START)).thenReturn(2);
        when(branches.quotaFor(BRANCH, ServiceGroup.MEDICAL, DAY, START)).thenReturn(3);
        when(appointments.countBookedByPet(PET)).thenReturn(1);

        service.bookAppointment(request(DAY, START, null));

        verify(appointments).saveAndFlush(any(Appointment.class));
    }

    @Test
    void customerWindowBoundariesAreInclusive() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        LocalDate tomorrow = LocalDate.of(2026, 10, 16);
        when(branches.openingRangesOn(BRANCH, tomorrow)).thenReturn(MORNING);

        service.bookAppointment(request(tomorrow, LocalTime.of(9, 0), null));

        verify(appointments).saveAndFlush(any(Appointment.class));
    }

    // ---------------------------------------------------------------- phạm vi, tồn tại

    @Test
    void receptionistOfAnotherBranchIsDeniedBeforeAnyLockOrRead() {
        asReceptionist();
        doThrow(new AccessDeniedScopeException("branch:" + BRANCH, "branch:9")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null)))
                .isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(locks, pets, catalog, branchLocks, branches, customers, appointments, codes);
    }

    @Test
    void customerAccountWithoutProfileIsBadDataBeforeAnyLock() {
        asCustomer();
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(locks, pets, branchLocks, appointments, codes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "VET", "CARETAKER"})
    void otherRolesNeverReachALock(String role) {
        when(principal.role()).thenReturn(role);

        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(locks, pets, branchLocks, appointments, codes, customers);
    }

    @Test
    void missingPetAndSomeoneElsesPetAreTheSameNotFoundAfterThePetLock() {
        asCustomer();
        when(pets.findPet(PET)).thenReturn(Optional.empty());
        String missing = notFoundMessage();

        when(pets.findPet(PET)).thenReturn(Optional.of(pet(OTHER_CUSTOMER, null)));
        String foreign = notFoundMessage();

        assertThat(missing).isEqualTo(foreign).isEqualTo("Không tìm thấy thú cưng #" + PET);
        verify(locks, org.mockito.Mockito.times(2)).lockPet(PET);
        verifyNoInteractions(catalog, branchLocks, branches, appointments, codes);
    }

    @Test
    void missingServiceIsNotFoundBeforeTheBranchLock() {
        asCustomer();
        when(pets.findPet(PET)).thenReturn(Optional.of(pet(CUSTOMER_ID, null)));
        when(catalog.findService(SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy dịch vụ #" + SERVICE);

        verifyNoInteractions(branchLocks, branches, appointments, codes);
    }

    @Test
    void missingBranchAndDraftBranchForCustomerAreTheSameNotFound() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.empty());
        String missing = notFoundMessage();
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.DRAFT));
        String draft = notFoundMessage();

        assertThat(missing).isEqualTo(draft).isEqualTo("Không tìm thấy chi nhánh #" + BRANCH);
        verifyNoInteractions(branches, appointments, codes, restrictions);
    }

    @Test
    void draftBranchForReceptionistIsRuleLhZeroOne() {
        asReceptionist();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.DRAFT);

        assertRule("BR-LH-01", "Chi nhánh chưa nhận đặt lịch (BR-LH-01)");
    }

    // ---------------------------------------------------------------- guard

    @Test
    void inactiveBoardingOrDisabledServiceIsRuleLhZeroOne() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        String message = "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)";

        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, false)));
        assertRule("BR-LH-01", message);
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.BOARDING, true)));
        assertRule("BR-LH-01", message);
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(ServiceGroup.MEDICAL, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(false);
        assertRule("BR-LH-01", message);
    }

    @Test
    void ruleLhZeroOneBeatsPendingLinkDecision() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(customers.findContact(CUSTOMER_ID)).thenReturn(Optional.of(contact(CUSTOMER_ID, true)));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(false);

        assertRule("BR-LH-01", "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)");
    }

    @Test
    void pendingLinkDecisionBlocksCustomer() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(customers.findContact(CUSTOMER_ID)).thenReturn(Optional.of(contact(CUSTOMER_ID, true)));

        assertRule("BR-TK-19", "Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám (BR-TK-19)");
        verifyNoInteractions(restrictions);
    }

    @Test
    void onlineRestrictionBlocksCustomer() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(restrictions.isRestricted(CUSTOMER_ID, NOW)).thenReturn(true);

        assertRule("BR-LH-09", "Bạn đang bị hạn chế đặt lịch online, vui lòng liên hệ chi nhánh để đặt lịch (BR-LH-09)");
    }

    @Test
    void deceasedPetBlocksBothRoles() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, LocalDate.of(2026, 10, 1)), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        assertRule("BR-KH-05", "Thú cưng đã mất, không đặt được lịch (BR-KH-05)");

        asReceptionist();
        assertRule("BR-KH-05", "Thú cưng đã mất, không đặt được lịch (BR-KH-05)");
    }

    @Test
    void customerOutsideWindowNamesTheConfiguredValues() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        LocalDate tomorrow = LocalDate.of(2026, 10, 16);
        when(branches.openingRangesOn(BRANCH, tomorrow)).thenReturn(MORNING);

        assertThatThrownBy(() -> service.bookAppointment(request(tomorrow, LocalTime.of(8, 30), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)");
        assertThatThrownBy(() -> service.bookAppointment(request(LocalDate.of(2026, 11, 15), START, null)))
                .hasMessageContaining("(BR-LH-04)");
        verify(codes, never()).nextCode();
    }

    @Test
    void receptionistCannotBookAStartedSlot() {
        asReceptionist();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        LocalDate today = LocalDate.of(2026, 10, 15);
        when(branches.openingRangesOn(BRANCH, today)).thenReturn(MORNING);

        assertThatThrownBy(() -> service.bookAppointment(request(today, LocalTime.of(9, 0), null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Khung giờ đã bắt đầu (BR-LH-04)");
    }

    @Test
    void windowIsCheckedBeforeTheSlotGrid() {
        asReceptionist();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);

        assertThatThrownBy(() -> service.bookAppointment(request(LocalDate.of(2026, 10, 14), LocalTime.of(8, 10),
                null))).hasMessage("Khung giờ đã bắt đầu (BR-LH-04)");
        verify(branches, never()).openingRangesOn(anyLong(), any());
    }

    @Test
    void slotOffTheGridOrOnAHolidayIsRuleLhZeroTwo() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        String message = "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)";

        assertThatThrownBy(() -> service.bookAppointment(request(DAY, LocalTime.of(8, 10), null))).hasMessage(message);
        assertThatThrownBy(() -> service.bookAppointment(request(DAY, LocalTime.of(9, 30).plusMinutes(30), null)))
                .as("khung 10:00 kết thúc sau giờ đóng").hasMessage(message);
        when(branches.openingRangesOn(BRANCH, DAY)).thenReturn(List.of());
        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null))).hasMessage(message);
        verify(appointments, never()).countBookedByPet(anyLong());
    }

    @Test
    void petLimitsAreCheckedInOrderCountThenSlotThenGroupDay() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(appointments.existsActiveSameSlot(PET, DAY, START)).thenReturn(true);
        when(appointments.existsActiveSameGroupDay(PET, "MEDICAL", DAY)).thenReturn(true);

        when(appointments.countBookedByPet(PET)).thenReturn(2);
        assertRule("BR-LH-05", "Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)");
        when(appointments.countBookedByPet(PET)).thenReturn(1);
        assertRule("BR-LH-05", "Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)");
        when(appointments.existsActiveSameSlot(PET, DAY, START)).thenReturn(false);
        assertRule("BR-LH-05", "Thú cưng đã có lịch Khám/Tiêm trong ngày này (BR-LH-05)");
        verify(appointments, never()).countActiveInSlot(anyLong(), any(), any(), any());
    }

    @Test
    void groomingSameDayNamesItsGroup() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.GROOMING, BranchStatus.ACTIVE);
        when(appointments.existsActiveSameGroupDay(PET, "GROOMING", DAY)).thenReturn(true);

        assertRule("BR-LH-05", "Thú cưng đã có lịch Thẩm mỹ trong ngày này (BR-LH-05)");
    }

    @Test
    void fullSlotIsRuleLhZeroThree() {
        asCustomer();
        givenBookable(pet(CUSTOMER_ID, null), ServiceGroup.MEDICAL, BranchStatus.ACTIVE);
        when(appointments.countActiveInSlot(BRANCH, "MEDICAL", DAY, START)).thenReturn(3);
        when(branches.quotaFor(BRANCH, ServiceGroup.MEDICAL, DAY, START)).thenReturn(3);

        assertRule("BR-LH-03", "Khung giờ đã hết chỗ (BR-LH-03)");

        when(branches.quotaFor(BRANCH, ServiceGroup.MEDICAL, DAY, START)).thenReturn(0);
        when(appointments.countActiveInSlot(BRANCH, "MEDICAL", DAY, START)).thenReturn(0);
        assertRule("BR-LH-03", "Khung giờ đã hết chỗ (BR-LH-03)");
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void oneWriteTransactionWithoutNoRollbackFor() throws NoSuchMethodException {
        Method method = AppointmentBookingService.class.getMethod("bookAppointment", BookAppointmentRequest.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
        assertThat(AppointmentBookingService.class.getAnnotation(Transactional.class)).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private void asCustomer() {
        when(principal.role()).thenReturn("CUSTOMER");
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER_ID));
    }

    private void asReceptionist() {
        when(principal.role()).thenReturn("RECEPTIONIST");
    }

    private void givenBookable(PetSummary pet, ServiceGroup group, BranchStatus status) {
        when(pets.findPet(PET)).thenReturn(Optional.of(pet));
        when(catalog.findService(SERVICE)).thenReturn(Optional.of(service(group, true)));
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(status));
        when(branches.isServiceEnabled(BRANCH, SERVICE)).thenReturn(true);
        when(customers.findContact(pet.customerId())).thenReturn(Optional.of(contact(pet.customerId(), false)));
        when(branches.openingRangesOn(BRANCH, DAY)).thenReturn(MORNING);
        when(branches.quotaFor(any(), any(), any(), any())).thenReturn(1);
    }

    private static PetSummary pet(long owner, LocalDate deceasedOn) {
        return new PetSummary(PET, owner, "Milu", Species.DOG, null, deceasedOn, BigDecimal.TEN);
    }

    private static ServiceInfo service(ServiceGroup group, boolean active) {
        return new ServiceInfo(SERVICE, "Khám tổng quát", group,
                group == ServiceGroup.MEDICAL ? MedicalType.EXAM : null, 150_000, active);
    }

    private static CustomerContact contact(long customerId, boolean linkDecisionPending) {
        return new CustomerContact(customerId, "Nguyễn Văn An", "0912345678", "an@example.com", ACCOUNT,
                linkDecisionPending);
    }

    private static BookAppointmentRequest request(LocalDate date, LocalTime start, String note) {
        return new BookAppointmentRequest(PET, BRANCH, SERVICE, date, start, note);
    }

    private Appointment savedAppointment() {
        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointments).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private String notFoundMessage() {
        try {
            service.bookAppointment(request(DAY, START, null));
        } catch (ResourceNotFoundException e) {
            return e.getMessage();
        }
        throw new AssertionError("phải ném ResourceNotFoundException");
    }

    /** Lỗi rule và không sinh mã, không lưu. */
    private void assertRule(String ruleId, String message) {
        assertThatThrownBy(() -> service.bookAppointment(request(DAY, START, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo(ruleId))
                .hasMessage(message);
        verify(codes, never()).nextCode();
        verify(appointments, never()).saveAndFlush(any());
    }
}
