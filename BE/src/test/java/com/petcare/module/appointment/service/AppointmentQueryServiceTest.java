package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.AppointmentListFilter;
import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.appointment.repository.AppointmentRepository;
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
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link AppointmentQueryService} (docs/adr/0044): phạm vi theo vai trò (khách ép hồ sơ của mình, nhân viên ép chi
 * nhánh trước mọi lần đọc), tên ghép theo id chứ không theo thứ tự trả về, trang rỗng không gọi module khác, cờ theo
 * người xem, kẹp trang, ranh giới transaction. Nội dung câu lọc (Specification) được kiểm trên Postgres ở
 * {@code AppointmentViewIT}.
 */
class AppointmentQueryServiceTest {

    private static final long ACCOUNT = 70L;
    private static final long OWN_CUSTOMER = 88L;
    private static final long OTHER_CUSTOMER = 89L;
    private static final long BRANCH = 3L;
    private static final long OTHER_BRANCH = 4L;
    /** 2026-10-15 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private static final AppointmentListFilter NO_FILTER =
            new AppointmentListFilter(null, null, null, null, null, null, null, null);

    private final BranchScope scope = mock(BranchScope.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final Clock clock = mock(Clock.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final AppointmentQueryService service = new AppointmentQueryService(scope, customers, pets, branches,
            catalog, configs, appointments, Mappers.getMapper(AppointmentMapper.class), clock);

    private List<Appointment> page = List.of();

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(clock.instant()).thenReturn(NOW);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(OWN_CUSTOMER));
        when(scope.resolve(any())).thenReturn(BRANCH);
        when(configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES)).thenReturn(3);
        when(configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS)).thenReturn(12);
        when(configs.getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES)).thenReturn(30);
        when(configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES)).thenReturn(30);
        when(appointments.findAll(anySpec(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(page, pageable, page.size());
        });
        when(branches.findBranch(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return Optional.of(new BranchSummary(id, "Chi nhánh " + id, "Địa chỉ", "0280000000", BranchStatus.ACTIVE,
                    false));
        });
        when(pets.findPets(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new PetSummary(id, OWN_CUSTOMER, "Thú " + id, Species.DOG, null, null, null))
                    .toList();
        });
        when(customers.findContacts(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new CustomerContact(id, "Khách " + id, null, null, null, false)).toList();
        });
        when(catalog.findServices(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new ServiceInfo(id, "Dịch vụ " + id, ServiceGroup.MEDICAL, MedicalType.EXAM,
                    100_000L, true)).toList();
        });
    }

    // ---------------------------------------------------------------- phạm vi danh sách

    @Test
    void customerListIsScopedToOwnProfileWithoutBranchScope() {
        asRole("CUSTOMER");
        page = List.of(appointment(1L, OWN_CUSTOMER, 10L, BRANCH, 20L, LocalTime.of(9, 0)));

        PageResponse<AppointmentResponse> result = service.listAppointments(NO_FILTER, 0, 20);

        assertThat(result.content()).hasSize(1);
        InOrder order = inOrder(customers, appointments);
        order.verify(customers).findCustomerIdByAccountId(ACCOUNT);
        order.verify(appointments).findAll(anySpec(), any(Pageable.class));
        verify(scope, never()).resolve(any());
        verify(scope, never()).check(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEPTIONIST", "BRANCH_MANAGER"})
    void staffListResolvesBranchFromTheRequest(String role) {
        asRole(role);
        AppointmentListFilter filter = new AppointmentListFilter(BRANCH, null, null, null, null, null, null, null);

        service.listAppointments(filter, 0, 20);

        InOrder order = inOrder(scope, appointments);
        order.verify(scope).resolve(BRANCH);
        order.verify(appointments).findAll(anySpec(), any(Pageable.class));
        verify(customers, never()).findCustomerIdByAccountId(anyLong());
    }

    @Test
    void staffAskingForAnotherBranchIsRejectedBeforeAnyRead() {
        asRole("RECEPTIONIST");
        when(scope.resolve(OTHER_BRANCH)).thenThrow(new AccessDeniedScopeException("branch:4", "branch:3"));
        AppointmentListFilter filter = new AppointmentListFilter(OTHER_BRANCH, null, null, null, null, null, null,
                null);

        assertThatThrownBy(() -> service.listAppointments(filter, 0, 20))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(appointments, pets, customers, catalog, branches, configs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "ADMIN", "SUPER_MANAGER", "GHOST"})
    void rolesOutsideTheListContractFailBeforeAnyRead(String role) {
        asRole(role);

        assertThatThrownBy(() -> service.listAppointments(NO_FILTER, 0, 20))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments, pets, customers, catalog, branches, configs);
        verify(scope, never()).resolve(any());
    }

    @Test
    void customerAccountWithoutProfileIs500() {
        asRole("CUSTOMER");
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listAppointments(NO_FILTER, 0, 20))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments);
    }

    // ---------------------------------------------------------------- trang rỗng, kẹp trang, [CFG]

    @Test
    void emptyPageCallsNoOtherModuleAndReadsNoConfig() {
        asRole("RECEPTIONIST");

        PageResponse<AppointmentResponse> result = service.listAppointments(NO_FILTER, 0, 20);

        assertThat(result.content()).isEmpty();
        verifyNoInteractions(pets, catalog, branches, configs);
        verify(customers, never()).findContacts(any());
    }

    @Test
    void pageAndSizeAreClampedAndSortedByVisitTime() {
        asRole("RECEPTIONIST");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        service.listAppointments(NO_FILTER, -3, 0);
        service.listAppointments(NO_FILTER, 2, 1000);
        service.listAppointments(NO_FILTER, 1, 7);

        verify(appointments, times(3)).findAll(anySpec(), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageNumber, Pageable::getPageSize)
                .containsExactly(tuple(0, 1), tuple(2, 100), tuple(1, 7));
        assertThat(pageable.getAllValues()).allSatisfy(captured ->
                assertThat(captured.getSort()).isEqualTo(AppointmentQueryService.ORDER));
        assertThat(AppointmentQueryService.ORDER.toString()).isEqualTo("slotDate: ASC,slotStart: ASC,id: ASC");
    }

    @Test
    void checkInWindowConfigIsReadOnlyWhenRequested() {
        asRole("RECEPTIONIST");

        service.listAppointments(new AppointmentListFilter(null, null, null, null, null, null, null, false), 0, 20);
        verify(configs, never()).getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES);
        verify(configs, never()).getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES);

        service.listAppointments(new AppointmentListFilter(null, null, null, null, null, null, null, true), 0, 20);
        verify(configs).getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES);
        verify(configs).getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES);
    }

    // ---------------------------------------------------------------- ghép tên theo lô

    @Test
    void namesAreJoinedByIdWithOneBatchCallPerModule() {
        asRole("RECEPTIONIST");
        page = List.of(
                appointment(1L, 801L, 11L, BRANCH, 21L, LocalTime.of(8, 0)),
                appointment(2L, 802L, 12L, BRANCH, 22L, LocalTime.of(8, 30)),
                appointment(3L, 801L, 11L, BRANCH, 21L, LocalTime.of(9, 0)));
        doReturn(List.of(
                new PetSummary(99L, 1L, "Thừa", Species.CAT, null, null, null),
                new PetSummary(12L, 802L, "Mèo", Species.CAT, null, null, null),
                new PetSummary(11L, 801L, "Chó", Species.DOG, null, null, null))).when(pets).findPets(any());
        doReturn(List.of(
                new CustomerContact(802L, "Bình", null, null, null, false),
                new CustomerContact(801L, "An", null, null, null, false))).when(customers).findContacts(any());

        PageResponse<AppointmentResponse> result = service.listAppointments(NO_FILTER, 0, 20);

        assertThat(result.content()).extracting(AppointmentResponse::appointmentId, AppointmentResponse::petName,
                AppointmentResponse::customerName, AppointmentResponse::serviceName, AppointmentResponse::branchName)
                .containsExactly(
                        tuple(1L, "Chó", "An", "Dịch vụ 21", "Chi nhánh 3"),
                        tuple(2L, "Mèo", "Bình", "Dịch vụ 22", "Chi nhánh 3"),
                        tuple(3L, "Chó", "An", "Dịch vụ 21", "Chi nhánh 3"));
        verify(pets).findPets(Set.of(11L, 12L));
        verify(customers).findContacts(Set.of(801L, 802L));
        verify(catalog).findServices(Set.of(21L, 22L));
        verify(branches, times(1)).findBranch(BRANCH);
        verify(pets, never()).findPet(anyLong());
        verify(customers, never()).findContact(anyLong());
    }

    @Test
    void missingNameIs500InsteadOfNull() {
        asRole("RECEPTIONIST");
        page = List.of(appointment(1L, 801L, 11L, BRANCH, 21L, LocalTime.of(8, 0)));
        doReturn(List.of()).when(pets).findPets(any());

        assertThatThrownBy(() -> service.listAppointments(NO_FILTER, 0, 20))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("thú cưng").hasMessageContaining("#11");
    }

    @Test
    void duplicateIdsFromABatchAre500() {
        asRole("RECEPTIONIST");
        page = List.of(appointment(1L, 801L, 11L, BRANCH, 21L, LocalTime.of(8, 0)));
        doReturn(List.of(
                new CustomerContact(801L, "An", null, null, null, false),
                new CustomerContact(801L, "An khác", null, null, null, false))).when(customers).findContacts(any());

        assertThatThrownBy(() -> service.listAppointments(NO_FILTER, 0, 20))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingBranchIs500() {
        asRole("RECEPTIONIST");
        page = List.of(appointment(1L, 801L, 11L, BRANCH, 21L, LocalTime.of(8, 0)));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listAppointments(NO_FILTER, 0, 20))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#3");
    }

    // ---------------------------------------------------------------- cờ theo người xem

    @Test
    void flagsFollowTheViewer() {
        // Khung 2026-10-15 20:00 VN: còn 11 giờ → khách không đổi được (cần 12), lễ tân đổi được; cả hai hủy được.
        Appointment evening = appointment(1L, OWN_CUSTOMER, 11L, BRANCH, 21L, LocalTime.of(20, 0));
        page = List.of(evening);

        asRole("CUSTOMER");
        assertThat(flags(service.listAppointments(NO_FILTER, 0, 20))).containsExactly(false, true);
        asRole("RECEPTIONIST");
        assertThat(flags(service.listAppointments(NO_FILTER, 0, 20))).containsExactly(true, true);
        asRole("BRANCH_MANAGER");
        assertThat(flags(service.listAppointments(NO_FILTER, 0, 20))).containsExactly(false, false);
    }

    @Test
    void readOnlyStaffDoesNotReadRescheduleConfig() {
        asRole("BRANCH_MANAGER");
        page = List.of(appointment(1L, 801L, 11L, BRANCH, 21L, LocalTime.of(20, 0)));

        service.listAppointments(NO_FILTER, 0, 20);

        verify(configs, never()).getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES);
        verify(configs, never()).getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS);
    }

    // ---------------------------------------------------------------- chi tiết

    @Test
    void customerSeesOwnAppointmentWithoutBranchScope() {
        asRole("CUSTOMER");
        when(appointments.findById(1L)).thenReturn(Optional.of(
                appointment(1L, OWN_CUSTOMER, 11L, BRANCH, 21L, LocalTime.of(20, 0))));

        AppointmentResponse response = service.getAppointment(1L);

        assertThat(response.appointmentId()).isEqualTo(1L);
        assertThat(response.canCancel()).isTrue();
        verify(scope, never()).check(any());
    }

    @Test
    void anotherCustomersAppointmentLooksExactlyLikeAMissingOne() {
        asRole("CUSTOMER");
        when(appointments.findById(1L)).thenReturn(Optional.of(
                appointment(1L, OTHER_CUSTOMER, 11L, BRANCH, 21L, LocalTime.of(20, 0))));
        when(appointments.findById(2L)).thenReturn(Optional.empty());

        Throwable foreign = catchThrowable(() -> service.getAppointment(1L));
        Throwable missing = catchThrowable(() -> service.getAppointment(2L));

        assertThat(foreign).isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy lịch hẹn #1");
        assertThat(missing).isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy lịch hẹn #2");
        verifyNoInteractions(pets, catalog, branches);
        verify(customers, never()).findContacts(any());
    }

    @Test
    void staffOfAnotherBranchGets403AfterTheLookup() {
        asRole("VET");
        when(appointments.findById(1L)).thenReturn(Optional.of(
                appointment(1L, OWN_CUSTOMER, 11L, OTHER_BRANCH, 21L, LocalTime.of(20, 0))));
        doThrow(new AccessDeniedScopeException("branch:4", "branch:3")).when(scope)
                .check(OTHER_BRANCH);

        assertThatThrownBy(() -> service.getAppointment(1L)).isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(pets, catalog, branches);
        verify(customers, never()).findCustomerIdByAccountId(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "BRANCH_MANAGER"})
    void readOnlyStaffSeeDetailWithoutFlags(String role) {
        asRole(role);
        when(appointments.findById(1L)).thenReturn(Optional.of(
                appointment(1L, OWN_CUSTOMER, 11L, BRANCH, 21L, LocalTime.of(20, 0))));

        AppointmentResponse response = service.getAppointment(1L);

        verify(scope).check(BRANCH);
        assertThat(response.canReschedule()).isFalse();
        assertThat(response.canCancel()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER"})
    void rolesOutsideTheDetailContractFailBeforeAnyRead(String role) {
        asRole(role);

        assertThatThrownBy(() -> service.getAppointment(1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments);
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void bothReadsRunInOneReadOnlyRepeatableReadTransaction() throws NoSuchMethodException {
        for (Method method : List.of(
                AppointmentQueryService.class.getMethod("listAppointments", AppointmentListFilter.class, int.class,
                        int.class),
                AppointmentQueryService.class.getMethod("getAppointment", Long.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.readOnly()).as(method.getName()).isTrue();
            assertThat(tx.isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
            assertThat(tx.noRollbackFor()).as(method.getName()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void asRole(String role) {
        when(principal.role()).thenReturn(role);
    }

    private static List<Boolean> flags(PageResponse<AppointmentResponse> result) {
        AppointmentResponse only = result.content().get(0);
        return List.of(only.canReschedule(), only.canCancel());
    }

    @SuppressWarnings("unchecked")
    private static Specification<Appointment> anySpec() {
        return any(Specification.class);
    }

    private static Appointment appointment(long id, long customerId, long petId, long branchId, long serviceId,
            LocalTime start) {
        Appointment appointment = Appointment.book("LH-261015-000" + id, customerId, petId, branchId, serviceId,
                ServiceGroup.MEDICAL, LocalDate.of(2026, 10, 15), start, AppointmentChannel.ONLINE, ACCOUNT, null);
        try {
            Field field = Appointment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(appointment, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return appointment;
    }
}
