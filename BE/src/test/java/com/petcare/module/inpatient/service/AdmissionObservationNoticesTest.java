package com.petcare.module.inpatient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;

/**
 * {@link AdmissionObservationNotices} (BR-LT-16; docs/adr/0149 mục 32): khách theo BR-TB-02, VET phụ trách nhận bản IN_APP
 * trừ khi chính họ ghi, đủ biến bắt buộc của mẫu V62, {@code ngay_ghi} theo ngày giờ Việt Nam. Dòng outbox thật và người
 * nhận từng dòng ở {@code AdmissionObservationIT}.
 */
class AdmissionObservationNoticesTest {

    private static final long CUSTOMER = 88L;
    private static final long CUSTOMER_ACCOUNT = 99L;
    private static final long VET = 45L;
    private static final long CARETAKER = 47L;
    /** 23:30 UTC ngày 23/11 = 06:30 ngày 24/11 giờ Việt Nam. */
    private static final Instant RECORDED_AT = Instant.parse("2026-11-23T23:30:00.123456Z");

    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final VisitQueryApi visits = mock(VisitQueryApi.class);
    private final AdmissionObservationNotices notices = new AdmissionObservationNotices(notifications, customers, pets,
            branches, accounts, visits);
    private final Admission admission = admission();

    @BeforeEach
    void setUp() {
        doReturn(Optional.of(new PetSummary(12L, CUSTOMER, "Mochi", Species.DOG, null, null, null))).when(pets)
                .findPet(12L);
        doReturn(Optional.of(new BranchSummary(3L, "Chi nhánh Cầu Giấy", "Hà Nội", "0900000000", BranchStatus.ACTIVE,
                false))).when(branches).findBranch(3L);
        doReturn(Optional.of(visit(VET))).when(visits).findVisit(9001L);
        withAccountCustomer();
    }

    @Test
    void anAccountCustomerGetsInAppAndEmailAndTheAssignedVetGetsInApp() {
        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        List<NotificationRequest> sent = sent();
        assertThat(sent).hasSize(3);
        NotificationRequest app = sent.get(0);
        assertThat(app.templateCode()).isEqualTo(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL_APP);
        assertThat(app.channel()).isEqualTo(Channel.IN_APP);
        assertThat(app.recipientAccountId()).isEqualTo(CUSTOMER_ACCOUNT);
        assertThat(app.linkUrl()).isEqualTo("/pets");
        NotificationRequest email = sent.get(1);
        assertThat(email.templateCode()).isEqualTo(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL);
        assertThat(email.channel()).isEqualTo(Channel.EMAIL);
        assertThat(email.recipientEmail()).isEqualTo("khach@example.com");
        assertThat(email.recipientAccountId()).isNull();
        NotificationRequest vet = sent.get(2);
        assertThat(vet.templateCode()).isEqualTo(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL_APP);
        assertThat(vet.channel()).isEqualTo(Channel.IN_APP);
        assertThat(vet.recipientAccountId()).isEqualTo(VET);
        assertThat(vet.linkUrl()).isEqualTo("/admin/inpatient");
    }

    @Test
    void theAssignedVetDoesNotNotifyThemselvesButTheCustomerStillHearsOfIt() {
        notices.enqueue(admission, RECORDED_AT, VET);

        List<NotificationRequest> sent = sent();
        assertThat(sent).hasSize(2);
        assertThat(sent).noneMatch(r -> Long.valueOf(VET).equals(r.recipientAccountId()));
    }

    @Test
    void aCounterCustomerWithAnEmailGetsOnlyAnEmail() {
        withCounterCustomer("quay@example.com");

        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        List<NotificationRequest> sent = sent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.get(0).recipientEmail()).isEqualTo("quay@example.com");
        assertThat(sent.get(1).recipientAccountId()).isEqualTo(VET);
    }

    @Test
    void aCounterCustomerWithoutAnEmailGetsNothingButTheVetIsStillTold() {
        withCounterCustomer(null);

        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        List<NotificationRequest> sent = sent();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).recipientAccountId()).isEqualTo(VET);
    }

    @Test
    void aPendingAccountFallsBackToTheCounterEmailAndNeverGetsAnInAppRow() {
        doReturn(Optional.of(new CustomerContact(CUSTOMER, "Nguyễn Văn An", "0911111111", "quay@example.com",
                CUSTOMER_ACCOUNT, false))).when(customers).findContact(CUSTOMER);
        doReturn(List.of(new AccountContact(CUSTOMER_ACCOUNT, "chua-xac-thuc@example.com", true))).when(accounts)
                .findContacts(List.of(CUSTOMER_ACCOUNT));

        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        assertThat(sent()).filteredOn(r -> r.recipientAccountId() != null && r.recipientAccountId() == CUSTOMER_ACCOUNT)
                .isEmpty();
        assertThat(sent()).filteredOn(r -> r.channel() == Channel.EMAIL).extracting(NotificationRequest::recipientEmail)
                .containsExactly("quay@example.com");
    }

    @Test
    void aCaseWithoutAnAssigneeNotifiesOnlyTheCustomer() {
        doReturn(Optional.of(visit(null))).when(visits).findVisit(9001L);

        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        assertThat(sent()).hasSize(2).noneMatch(r -> r.linkUrl() != null && r.linkUrl().equals("/admin/inpatient"));
    }

    @Test
    void thePayloadCarriesEveryVariableOfTheV62TemplatesWithTheVietnamDate() {
        notices.enqueue(admission, RECORDED_AT, CARETAKER);

        for (NotificationRequest request : sent()) {
            assertThat(request.payload()).containsEntry("ten_khach", "Nguyễn Văn An")
                    .containsEntry("ten_thu_cung", "Mochi").containsEntry("chi_nhanh", "Chi nhánh Cầu Giấy")
                    .containsEntry("ma_noi_tru", "NT-2611-00051").containsEntry("ngay_ghi", "24/11/2026");
            assertThat(request.payload().keySet()).containsExactlyInAnyOrderElementsOf(
                    Set.of("ten_khach", "ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi"));
        }
    }

    @Test
    void aMissingRequiredVariableRollsBackInsteadOfQueuingARowThatST20WouldFail() {
        doReturn(Optional.of(new BranchSummary(3L, null, "Hà Nội", "0900000000", BranchStatus.ACTIVE, false))).when(branches)
                .findBranch(3L);

        assertThatThrownBy(() -> notices.enqueue(admission, RECORDED_AT, CARETAKER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chi_nhanh");

        verifyNoInteractions(notifications);
    }

    @Test
    void missingRelatedDataIsCorruptDataNotASilentSkip() {
        doReturn(Optional.empty()).when(pets).findPet(12L);
        assertThatThrownBy(() -> notices.enqueue(admission, RECORDED_AT, CARETAKER))
                .isInstanceOf(IllegalStateException.class);

        doReturn(Optional.of(new PetSummary(12L, CUSTOMER, "Mochi", Species.DOG, null, null, null))).when(pets)
                .findPet(12L);
        doReturn(Optional.empty()).when(visits).findVisit(9001L);
        assertThatThrownBy(() -> notices.enqueue(admission, RECORDED_AT, CARETAKER))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(notifications);
    }

    // ---------------------------------------------------------------- helpers

    private List<NotificationRequest> sent() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).enqueue(captor.capture());
        return captor.getAllValues();
    }

    private void withAccountCustomer() {
        doReturn(Optional.of(new CustomerContact(CUSTOMER, "Nguyễn Văn An", "0911111111", null, CUSTOMER_ACCOUNT,
                false))).when(customers).findContact(CUSTOMER);
        doReturn(List.of(new AccountContact(CUSTOMER_ACCOUNT, "khach@example.com", false))).when(accounts)
                .findContacts(List.of(CUSTOMER_ACCOUNT));
    }

    private void withCounterCustomer(String email) {
        doReturn(Optional.of(new CustomerContact(CUSTOMER, "Nguyễn Văn An", "0911111111", email, null, false)))
                .when(customers).findContact(CUSTOMER);
    }

    private static VisitSummary visit(Long assignee) {
        return new VisitSummary(9001L, 3L, CUSTOMER, 12L, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, assignee);
    }

    private static Admission admission() {
        Admission a = Admission.admit("NT-2611-00051", 9001L, 12L, 3L, null, 17L, 41L, 200_000L, 7L,
                Instant.parse("2026-11-22T02:00:00Z"));
        ReflectionTestUtils.setField(a, "id", 51L);
        return a;
    }
}
