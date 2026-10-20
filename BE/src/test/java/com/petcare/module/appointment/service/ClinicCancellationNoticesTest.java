package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;

/**
 * {@link ClinicCancellationNotices} (docs/adr/0052 Q2, Q9; mapping M3, M4): payload so nguyên map, giờ hẹn định dạng
 * theo giờ Việt Nam không đổi zone, ma trận người nhận (tài khoản / tài khoản PENDING / chỉ email hồ sơ / không kênh),
 * một lần gọi theo lô cho mỗi module, id thiếu / trùng → lỗi, rỗng → không gọi gì. Thông báo thật qua ST20:
 * {@code ClinicCancellationIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClinicCancellationNoticesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final ClinicCancellationNotices notices =
            new ClinicCancellationNotices(notifications, customers, pets, catalog, branches, accounts);

    @BeforeEach
    void setUp() {
        when(branches.findBranch(3L)).thenReturn(Optional.of(
                new BranchSummary(3L, "Pet Care Quận 1", "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));
        when(catalog.findServices(anyCollection())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(id -> new ServiceInfo(id, "Dịch vụ " + id, ServiceGroup.MEDICAL, null, 100_000L, true)).toList());
        when(pets.findPets(anyCollection())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(id -> new PetSummary(id, 7L, "Thú " + id, Species.DOG, null, null, null)).toList());
        when(accounts.findContacts(anyCollection())).thenReturn(List.of());
    }

    private static Appointment appointment(long id, long customerId, long petId, long serviceId, LocalTime start) {
        Appointment appointment = Appointment.book("LH-261025-" + id, customerId, petId, 3L, serviceId,
                ServiceGroup.MEDICAL, DAY, start, AppointmentChannel.ONLINE, 1L, null);
        try {
            Field f = Appointment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(appointment, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return appointment;
    }

    private static CustomerContact contact(long id, String email, Long accountId) {
        return new CustomerContact(id, "Nguyễn Văn " + id, "0901234567", email, accountId, false);
    }

    // ---- payload (M3) ---------------------------------------------------------------------------------------------

    @Test
    void payloadHasEveryTemplateVariableInOrder() {
        Map<String, Object> payload = ClinicCancellationNotices.payload(contact(7L, null, null), "Milu",
                "Khám tổng quát", "Pet Care Quận 1", LocalDateTime.of(2026, 10, 25, 9, 30),
                ClinicCancelCause.HOLIDAY_ADDED);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("ten_khach", "Nguyễn Văn 7");
        expected.put("ten_thu_cung", "Milu");
        expected.put("dich_vu", "Khám tổng quát");
        expected.put("chi_nhanh", "Pet Care Quận 1");
        expected.put("gio_hen", "09:30 25/10/2026");
        expected.put("ly_do", "Chi nhánh nghỉ vào ngày hẹn");
        assertThat(payload).containsExactlyEntriesOf(expected);
        assertThat(payload.keySet()).containsAll(ClinicCancellationNotices.REQUIRED_VARS);
    }

    /** Giờ hẹn vốn là giờ Việt Nam: định dạng thẳng, không qua Instant / zone JVM (biên đầu và cuối ngày). */
    @Test
    void slotTimeIsFormattedWithoutZoneConversion() {
        assertThat(ClinicCancellationNotices.payload(contact(7L, null, null), "a", "b", "c",
                LocalDateTime.of(2026, 1, 1, 0, 0), ClinicCancelCause.PET_DECEASED)).containsEntry("gio_hen",
                "00:00 01/01/2026");
        assertThat(ClinicCancellationNotices.payload(contact(7L, null, null), "a", "b", "c",
                LocalDateTime.of(2026, 12, 31, 23, 30), ClinicCancelCause.PET_DECEASED)).containsEntry("gio_hen",
                "23:30 31/12/2026");
    }

    /** {@code enqueue} không kiểm biến bắt buộc (chỉ ST20, sau commit): kiểm ở đây để rollback thay vì FAILED âm thầm. */
    @Test
    void nullRequiredValueIsRejected() {
        assertThatThrownBy(() -> ClinicCancellationNotices.payload(contact(7L, null, null), null, "b", "c",
                LocalDateTime.of(2026, 10, 25, 9, 0), ClinicCancelCause.PET_DECEASED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ten_thu_cung");
    }

    // ---- người nhận (M4) ------------------------------------------------------------------------------------------

    @Test
    void verifiedAccountGetsInAppAndEmailToTheAccountEmail() {
        Map<String, Object> payload = Map.of("k", "v");

        List<NotificationRequest> requests = ClinicCancellationNotices.requests(
                contact(7L, null, 70L), new AccountContact(70L, "khach@example.com", false), payload);

        assertThat(requests).containsExactly(
                new NotificationRequest(NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC_APP, Channel.IN_APP,
                        70L, null, payload, "/appointments"),
                new NotificationRequest(NotificationTemplateCode.APPOINTMENT_CANCELLED_BY_CLINIC, Channel.EMAIL,
                        null, "khach@example.com", payload, null));
    }

    /** Hồ sơ đã liên kết có email hồ sơ khác email tài khoản: dùng email tài khoản (đã xác thực). */
    @Test
    void accountEmailWinsOverProfileEmail() {
        List<NotificationRequest> requests = ClinicCancellationNotices.requests(
                contact(7L, "cu@example.com", 70L), new AccountContact(70L, "moi@example.com", false), Map.of());

        assertThat(requests).extracting(NotificationRequest::recipientEmail).containsExactly(null, "moi@example.com");
    }

    @Test
    void pendingAccountIsTreatedAsNoAccount() {
        assertThat(ClinicCancellationNotices.requests(contact(7L, null, 70L),
                new AccountContact(70L, "chua-xac-thuc@example.com", true), Map.of())).isEmpty();
        assertThat(ClinicCancellationNotices.requests(contact(7L, "quay@example.com", 70L),
                new AccountContact(70L, "chua-xac-thuc@example.com", true), Map.of()))
                .extracting(NotificationRequest::channel, NotificationRequest::recipientAccountId,
                        NotificationRequest::recipientEmail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Channel.EMAIL, null, "quay@example.com"));
    }

    @Test
    void counterProfileWithEmailGetsEmailOnly() {
        assertThat(ClinicCancellationNotices.requests(contact(7L, "quay@example.com", null), null, Map.of()))
                .extracting(NotificationRequest::channel, NotificationRequest::recipientAccountId,
                        NotificationRequest::recipientEmail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Channel.EMAIL, null, "quay@example.com"));
    }

    @Test
    void noChannelMeansNoRequest() {
        assertThat(ClinicCancellationNotices.requests(contact(7L, null, null), null, Map.of())).isEmpty();
        assertThat(ClinicCancellationNotices.requests(contact(7L, "  ", null), null, Map.of())).isEmpty();
    }

    // ---- enqueueFor -----------------------------------------------------------------------------------------------

    @Test
    void enqueuesPerAppointmentWithItsOwnNames(CapturedOutput output) {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(
                contact(7L, null, 70L), contact(8L, "quay@example.com", null), contact(9L, null, null)));
        when(accounts.findContacts(List.of(70L))).thenReturn(List.of(new AccountContact(70L, "a@example.com", false)));

        notices.enqueueFor(List.of(
                appointment(1L, 7L, 21L, 31L, LocalTime.of(9, 0)),
                appointment(2L, 8L, 22L, 32L, LocalTime.of(10, 30)),
                appointment(3L, 9L, 23L, 33L, LocalTime.of(11, 0))), ClinicCancelCause.HOLIDAY_ADDED);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(3)).enqueue(sent.capture());
        assertThat(sent.getAllValues()).extracting(NotificationRequest::channel)
                .containsExactly(Channel.IN_APP, Channel.EMAIL, Channel.EMAIL);
        assertThat(sent.getAllValues().get(0).payload()).containsEntry("ten_thu_cung", "Thú 21")
                .containsEntry("dich_vu", "Dịch vụ 31").containsEntry("gio_hen", "09:00 25/10/2026");
        assertThat(sent.getAllValues().get(2).payload()).containsEntry("ten_thu_cung", "Thú 22")
                .containsEntry("dich_vu", "Dịch vụ 32").containsEntry("gio_hen", "10:30 25/10/2026")
                .containsEntry("ten_khach", "Nguyễn Văn 8");
        assertThat(sent.getAllValues().get(2).recipientEmail()).isEqualTo("quay@example.com");
        assertThat(output).contains("APPOINTMENT_CLINIC_CANCEL_NO_CHANNEL appointmentId=3 customerId=9");
        verify(customers, times(1)).findContacts(anyCollection());
        verify(pets, times(1)).findPets(anyCollection());
        verify(catalog, times(1)).findServices(anyCollection());
        verify(branches, times(1)).findBranch(3L);
        verify(accounts, times(1)).findContacts(List.of(70L));
    }

    @Test
    void emptyListCallsNoModule() {
        notices.enqueueFor(List.of(), ClinicCancelCause.PET_DECEASED);

        verifyNoInteractions(notifications, customers, pets, catalog, branches, accounts);
    }

    @Test
    void lookupsAreBatchedByOneHundred() {
        when(customers.findContacts(anyCollection())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0))
                .stream().map(id -> contact(id, null, null)).toList());
        List<Appointment> cancelled = LongStream.rangeClosed(1, 150)
                .mapToObj(i -> appointment(i, 1000 + i, 2000 + i, 31L, LocalTime.of(9, 0))).toList();

        notices.enqueueFor(cancelled, ClinicCancelCause.HOLIDAY_ADDED);

        verify(customers, times(2)).findContacts(anyCollection());
        verify(pets, times(2)).findPets(anyCollection());
        verify(catalog, times(1)).findServices(anyCollection());
        verify(accounts, never()).findContacts(anyCollection());
    }

    @Test
    void missingContactIsCorruptDataAndNothingIsEnqueued() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> notices.enqueueFor(List.of(appointment(1L, 7L, 21L, 31L, LocalTime.of(9, 0))),
                ClinicCancelCause.PET_DECEASED)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hồ sơ khách");
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void duplicateIdFromAModuleIsRejected() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(contact(7L, null, null),
                contact(7L, null, null)));

        assertThatThrownBy(() -> notices.enqueueFor(List.of(appointment(1L, 7L, 21L, 31L, LocalTime.of(9, 0))),
                ClinicCancelCause.PET_DECEASED)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trùng id");
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void missingBranchIsRejected() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(contact(7L, null, null)));
        when(branches.findBranch(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notices.enqueueFor(List.of(appointment(1L, 7L, 21L, 31L, LocalTime.of(9, 0))),
                ClinicCancelCause.PET_DECEASED)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chi nhánh");
    }

    @Test
    void missingAccountOfLinkedProfileIsRejected() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(contact(7L, null, 70L)));

        assertThatThrownBy(() -> notices.enqueueFor(List.of(appointment(1L, 7L, 21L, 31L, LocalTime.of(9, 0))),
                ClinicCancelCause.PET_DECEASED)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tài khoản");
        verify(notifications, never()).enqueue(any());
    }
}
