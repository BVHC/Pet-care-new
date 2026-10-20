package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
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

/**
 * {@link AppointmentNoticeTargets} (docs/adr/0047 Q2, docs/adr/0051 Q5): chọn người nhận BR-TB-02 với mã mẫu bất kỳ, và
 * {@code resolve} trả đủ thú (kể cả ngày mất), dịch vụ, chi nhánh, tài khoản theo đúng thứ tự đầu vào. Phần chia lô / trùng
 * / thiếu id được {@code ClinicCancellationNoticesTest} canh qua lớp gọi.
 */
class AppointmentNoticeTargetsTest {

    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final AppointmentNoticeTargets targets =
            new AppointmentNoticeTargets(customers, pets, catalog, branches, accounts);

    private static CustomerContact contact(long id, String email, Long accountId) {
        return new CustomerContact(id, "Khách " + id, "0901234567", email, accountId, false);
    }

    @Test
    void verifiedAccountGetsInAppAndAccountEmailWithGivenCodes() {
        List<NotificationRequest> requests = AppointmentNoticeTargets.requests(contact(7L, "quay@example.com", 70L),
                new AccountContact(70L, "acc@example.com", false), Map.of("k", "v"), "MAIL_CODE", "APP_CODE", "/x");

        assertThat(requests).extracting(NotificationRequest::templateCode, NotificationRequest::channel,
                NotificationRequest::recipientAccountId, NotificationRequest::recipientEmail, NotificationRequest::linkUrl)
                .containsExactly(tuple("APP_CODE", Channel.IN_APP, 70L, null, "/x"),
                        tuple("MAIL_CODE", Channel.EMAIL, null, "acc@example.com", null));
        assertThat(requests).allSatisfy(r -> assertThat(r.payload()).containsEntry("k", "v"));
    }

    @Test
    void pendingAccountIsTreatedAsNoAccount() {
        assertThat(AppointmentNoticeTargets.requests(contact(7L, null, 70L),
                new AccountContact(70L, "acc@example.com", true), Map.of(), "M", "A", "/x")).isEmpty();
        assertThat(AppointmentNoticeTargets.requests(contact(7L, "quay@example.com", 70L),
                new AccountContact(70L, "acc@example.com", true), Map.of(), "M", "A", "/x"))
                .extracting(NotificationRequest::channel, NotificationRequest::recipientEmail)
                .containsExactly(tuple(Channel.EMAIL, "quay@example.com"));
    }

    @Test
    void counterProfileWithoutEmailHasNoChannel() {
        assertThat(AppointmentNoticeTargets.requests(contact(7L, null, null), null, Map.of(), "M", "A", "/x")).isEmpty();
        assertThat(AppointmentNoticeTargets.requests(contact(7L, " ", null), null, Map.of(), "M", "A", "/x")).isEmpty();
    }

    @Test
    void resolveKeepsInputOrderAndCarriesPetDeathAndBranchContact() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(contact(7L, null, 70L),
                contact(8L, "b@example.com", null)));
        when(pets.findPets(anyCollection())).thenReturn(List.of(
                new PetSummary(21L, 7L, "Milu", Species.DOG, null, null, null),
                new PetSummary(22L, 8L, "Mướp", Species.CAT, null, LocalDate.of(2026, 10, 1), null)));
        when(catalog.findServices(anyCollection())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(id -> new ServiceInfo(id, "Dịch vụ " + id, ServiceGroup.MEDICAL, null, 1L, false)).toList());
        when(branches.findBranch(3L)).thenReturn(Optional.of(
                new BranchSummary(3L, "Pet Care Quận 1", "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));
        when(accounts.findContacts(List.of(70L))).thenReturn(List.of(new AccountContact(70L, "a@example.com", false)));

        List<AppointmentNoticeTargets.Target> resolved = targets.resolve(List.of(
                new AppointmentNoticeTargets.Subject(2L, 8L, 22L, 31L, 3L),
                new AppointmentNoticeTargets.Subject(1L, 7L, 21L, 31L, 3L)));

        assertThat(resolved).extracting(t -> t.subject().appointmentId()).containsExactly(2L, 1L);
        assertThat(resolved.get(0).pet().deceased()).isTrue();
        assertThat(resolved.get(0).account()).isNull();
        assertThat(resolved.get(1).account().email()).isEqualTo("a@example.com");
        assertThat(resolved.get(1).service().active()).as("dịch vụ đã ngừng vẫn trả tên (R10)").isFalse();
        assertThat(resolved.get(1).branch().address()).isEqualTo("1 Lê Lợi");
        verify(branches).findBranch(3L);
    }

    @Test
    void emptyInputCallsNoModule() {
        assertThat(targets.resolve(List.of())).isEmpty();

        verifyNoInteractions(customers, pets, catalog, branches, accounts);
    }

    @Test
    void missingBranchIsCorruptData() {
        when(customers.findContacts(anyCollection())).thenReturn(List.of(contact(7L, null, null)));
        when(pets.findPets(anyCollection())).thenReturn(List.of(new PetSummary(21L, 7L, "Milu", Species.DOG, null,
                null, null)));
        when(catalog.findServices(anyCollection())).thenReturn(List.of(new ServiceInfo(31L, "Khám", ServiceGroup.MEDICAL,
                null, 1L, true)));
        when(branches.findBranch(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> targets.resolve(List.of(new AppointmentNoticeTargets.Subject(1L, 7L, 21L, 31L, 3L))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chi nhánh");
        verify(accounts, never()).findContacts(anyCollection());
    }
}
