package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.appointment.service.AppointmentReminderNotices.Outcome;
import com.petcare.module.appointment.service.AppointmentReminderServiceTest.Row;
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
 * {@link AppointmentReminderNotices} (docs/adr/0051): payload từ chính dòng (mã, giờ hẹn), tên / địa chỉ / SĐT chi nhánh
 * hiện tại, mã mẫu nhắc, ma trận người nhận BR-TB-02, thú đã mất không gửi (Q10), không kênh không gửi (Q3), biến bắt
 * buộc thiếu → lỗi trước khi ghi outbox.
 */
class AppointmentReminderNoticesTest {

    private static final Row ROW = new Row(801L, "LH-261019-0801", 7L, 21L, 31L, 3L, LocalDate.of(2026, 10, 20),
            LocalTime.of(8, 30));

    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final AppointmentReminderNotices notices =
            new AppointmentReminderNotices(notifications, customers, pets, catalog, branches, accounts);

    @BeforeEach
    void setUp() {
        when(pets.findPets(anyCollection())).thenReturn(List.of(
                new PetSummary(21L, 7L, "Milu", Species.DOG, null, null, null)));
        when(catalog.findServices(anyCollection())).thenReturn(List.of(
                new ServiceInfo(31L, "Khám tổng quát", ServiceGroup.MEDICAL, null, 150_000L, true)));
        when(branches.findBranch(3L)).thenReturn(Optional.of(
                new BranchSummary(3L, "Pet Care Quận 1", "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));
    }

    private void contact(String email, Long accountId) {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(
                new CustomerContact(7L, "Nguyễn Văn A", "0901234567", email, accountId, false)));
    }

    @Test
    void accountGetsInAppAndAccountEmailWithFullPayload() {
        contact(null, 70L);
        when(accounts.findContacts(List.of(70L))).thenReturn(List.of(new AccountContact(70L, "a@example.com", false)));

        assertThat(notices.enqueueFor(ROW)).isEqualTo(Outcome.SENT);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(sent.capture());
        assertThat(sent.getAllValues()).extracting(NotificationRequest::templateCode, NotificationRequest::channel,
                NotificationRequest::recipientAccountId, NotificationRequest::recipientEmail, NotificationRequest::linkUrl)
                .containsExactly(
                        tuple(NotificationTemplateCode.APPOINTMENT_REMINDER_APP, Channel.IN_APP, 70L, null,
                                "/appointments"),
                        tuple(NotificationTemplateCode.APPOINTMENT_REMINDER, Channel.EMAIL, null, "a@example.com",
                                null));
        assertThat(sent.getValue().payload()).containsOnlyKeys("ten_khach", "ten_thu_cung", "dich_vu", "chi_nhanh",
                        "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen")
                .containsEntry("ten_khach", "Nguyễn Văn A").containsEntry("ten_thu_cung", "Milu")
                .containsEntry("dich_vu", "Khám tổng quát").containsEntry("chi_nhanh", "Pet Care Quận 1")
                .containsEntry("dia_chi_chi_nhanh", "1 Lê Lợi").containsEntry("so_dien_thoai_chi_nhanh", "0281234567")
                .containsEntry("gio_hen", "08:30 20/10/2026").containsEntry("ma_lich_hen", "LH-261019-0801");
        assertThat(sent.getAllValues().get(0).payload()).isSameAs(sent.getValue().payload());
    }

    @Test
    void counterProfileWithEmailGetsEmailOnly() {
        contact("quay@example.com", null);

        assertThat(notices.enqueueFor(ROW)).isEqualTo(Outcome.SENT);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(sent.capture());
        assertThat(sent.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.getValue().recipientEmail()).isEqualTo("quay@example.com");
        verify(accounts, never()).findContacts(anyCollection());
    }

    @Test
    void noChannelEnqueuesNothing() {
        contact(null, null);

        assertThat(notices.enqueueFor(ROW)).isEqualTo(Outcome.NO_CHANNEL);
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void deceasedPetEnqueuesNothing() {
        contact("quay@example.com", null);
        when(pets.findPets(anyCollection())).thenReturn(List.of(
                new PetSummary(21L, 7L, "Milu", Species.DOG, null, LocalDate.of(2026, 10, 18), null)));

        assertThat(notices.enqueueFor(ROW)).isEqualTo(Outcome.PET_DECEASED);
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void missingRequiredVariableFailsBeforeEnqueue() {
        contact("quay@example.com", null);
        when(branches.findBranch(3L)).thenReturn(Optional.of(
                new BranchSummary(3L, "Pet Care Quận 1", null, "0281234567", BranchStatus.ACTIVE, false)));

        assertThatThrownBy(() -> notices.enqueueFor(ROW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dia_chi_chi_nhanh");
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void requiredVariablesMatchTheTemplateContract() {
        assertThat(AppointmentReminderNotices.REQUIRED_VARS).containsExactly("ten_thu_cung", "dich_vu", "chi_nhanh",
                "dia_chi_chi_nhanh", "so_dien_thoai_chi_nhanh", "gio_hen", "ma_lich_hen");
    }
}
