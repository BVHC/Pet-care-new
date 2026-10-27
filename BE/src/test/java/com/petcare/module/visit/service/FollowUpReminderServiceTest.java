package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository.DueFollowUp;

/** {@link FollowUpReminderService} (docs/adr/0082): cùng khuôn với {@link VaccineReminderServiceTest}. */
class FollowUpReminderServiceTest {

    private static final long VISIT = 601L;
    private static final long PET = 9L;
    private static final long CUSTOMER = 7L;
    private static final long BRANCH = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate DUE = LocalDate.of(2026, 10, 30);
    private static final DueDateWindow WINDOW = DueDateWindow.at(TODAY, 3);
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:10.123456789Z");
    private static final Instant AT = NOW.truncatedTo(ChronoUnit.MICROS);

    private final MedicalRecordRepository records = mock(MedicalRecordRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CareTaskApi careTasks = mock(CareTaskApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final FollowUpReminderService service = new FollowUpReminderService(records, pets, customers, accounts,
            branches, notifications, careTasks, configs, Clock.fixed(NOW, ZoneOffset.UTC));

    record FollowUp(Long getVisitId, Long getPetId, Long getBranchId, LocalDate getFollowUpDate)
            implements DueFollowUp {}

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static PetSummary pet(LocalDate deceasedOn) {
        return new PetSummary(PET, CUSTOMER, "Mi", Species.CAT, null, deceasedOn, null);
    }

    private static CustomerContact contact(String email, Long accountId, String phone) {
        return new CustomerContact(CUSTOMER, "Nguyễn Văn A", phone, email, accountId, false);
    }

    private void due(PetSummary pet, CustomerContact contact, AccountContact account) {
        when(records.findPetIdOfVisit(VISIT)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet));
        when(records.lockDueForReminder(VISIT, TODAY, WINDOW.to())).thenReturn(Optional.of(VISIT));
        when(records.findDueFollowUp(VISIT)).thenReturn(Optional.of(new FollowUp(VISIT, PET, BRANCH, DUE)));
        when(records.markFollowUpReminded(VISIT, AT)).thenReturn(1);
        when(records.markFollowUpCancelled(VISIT, AT)).thenReturn(1);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact));
        if (contact.accountId() != null) {
            when(accounts.findContacts(List.of(contact.accountId()))).thenReturn(List.of(account));
        }
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh Q1", "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));
    }

    @Test
    void windowIsTodayThroughConfiguredDays() {
        when(configs.getInt(ConfigKey.REMINDER_FOLLOW_UP_DAYS_BEFORE)).thenReturn(3);

        assertThat(service.window()).isEqualTo(WINDOW);
    }

    @Test
    void findDueIdsPicksFirstOrNextBatchQuery() {
        when(records.findDueForReminderIds(TODAY, WINDOW.to(), 50)).thenReturn(List.of(1L));
        when(records.findDueForReminderIdsAfter(TODAY, WINDOW.to(), 1L, 50)).thenReturn(List.of());

        assertThat(service.findDueIds(WINDOW, 0, 50)).containsExactly(1L);
        assertThat(service.findDueIds(WINDOW, 1L, 50)).isEmpty();
    }

    @Test
    void remindsInOrderShareLockRowLockReadMarkThenEnqueue() {
        due(pet(null), contact(null, 5L, "0901234567"), new AccountContact(5L, "a@x.vn", false));

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.REMINDED);

        InOrder order = inOrder(records, pets, notifications);
        order.verify(records).findPetIdOfVisit(VISIT);
        order.verify(pets).findPetForShare(PET);
        order.verify(records).lockDueForReminder(VISIT, TODAY, WINDOW.to());
        order.verify(records).findDueFollowUp(VISIT);
        order.verify(records).markFollowUpReminded(VISIT, AT);
        order.verify(notifications, times(2)).enqueue(any());
        verify(records, never()).markFollowUpCancelled(anyLong(), any());
        verify(records, never()).save(any());
        verifyNoInteractions(careTasks);
    }

    @Test
    void accountGetsInAppWithBranchLinkWithoutServiceAndEmail() {
        due(pet(null), contact(null, 5L, null), new AccountContact(5L, "a@x.vn", false));

        service.remind(VISIT, WINDOW);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(sent.capture());
        NotificationRequest app = sent.getAllValues().get(0);
        NotificationRequest email = sent.getAllValues().get(1);
        assertThat(app.templateCode()).isEqualTo("FOLLOW_UP_REMINDER_APP");
        assertThat(app.channel()).isEqualTo(Channel.IN_APP);
        assertThat(app.linkUrl()).isEqualTo("/booking?petId=9&branchId=3");
        assertThat(email.templateCode()).isEqualTo("FOLLOW_UP_REMINDER");
        assertThat(email.recipientEmail()).isEqualTo("a@x.vn");
        assertThat(app.payload()).containsEntry("ten_khach", "Nguyễn Văn A").containsEntry("ten_thu_cung", "Mi")
                .containsEntry("ngay_tai_kham", "30/10/2026").containsEntry("chi_nhanh", "Chi nhánh Q1")
                .containsEntry("so_dien_thoai_chi_nhanh", "0281234567");
    }

    @Test
    void profileWithoutEmailCreatesFollowUpTaskForTheBranchOfTheVisit() {
        due(pet(null), contact(null, null, "0901234567"), null);

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.TASK_CREATED);

        InOrder order = inOrder(records, careTasks);
        order.verify(records).markFollowUpReminded(VISIT, AT);
        order.verify(careTasks).create(new NewCareTask(CareTaskType.FOLLOW_UP_DUE, VISIT, BRANCH, CUSTOMER, PET, DUE));
        verifyNoInteractions(notifications);
    }

    @Test
    void profileWithoutEmailAndPhoneIsMarkedButNothingIsSent() {
        due(pet(null), contact(null, null, null), null);

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);

        verify(records).markFollowUpReminded(VISIT, AT);
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void deceasedPetCancelsTheReminderInsteadOfMarkingItSent() {
        when(records.findPetIdOfVisit(VISIT)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(TODAY.minusDays(1))));
        when(records.lockDueForReminder(VISIT, TODAY, WINDOW.to())).thenReturn(Optional.of(VISIT));
        when(records.findDueFollowUp(VISIT)).thenReturn(Optional.of(new FollowUp(VISIT, PET, BRANCH, DUE)));
        when(records.markFollowUpCancelled(VISIT, AT)).thenReturn(1);

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);

        verify(records, never()).markFollowUpReminded(anyLong(), any());
        verifyNoInteractions(customers, accounts, branches, notifications, careTasks);
    }

    @Test
    void unknownVisitIsSkippedBeforeAnyLock() {
        when(records.findPetIdOfVisit(VISIT)).thenReturn(Optional.empty());

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verifyNoInteractions(pets, notifications, careTasks);
    }

    @Test
    void lockMissIsSkippedWithoutAnyWrite() {
        when(records.findPetIdOfVisit(VISIT)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null)));
        when(records.lockDueForReminder(VISIT, TODAY, WINDOW.to())).thenReturn(Optional.empty());

        assertThat(service.remind(VISIT, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verify(records, never()).findDueFollowUp(anyLong());
        verify(records, never()).markFollowUpReminded(anyLong(), any());
        verify(records, never()).markFollowUpCancelled(anyLong(), any());
        verifyNoInteractions(customers, notifications, careTasks);
    }

    @Test
    void missingRequiredVariableFailsBeforeTheFlagIsSet() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, null, "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));

        assertThatThrownBy(() -> service.remind(VISIT, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chi_nhanh");

        verify(records, never()).markFollowUpReminded(anyLong(), any());
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void flagNotHittingExactlyOneRowIsAnErrorAndSendsNothing() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        when(records.markFollowUpReminded(VISIT, AT)).thenReturn(0);

        assertThatThrownBy(() -> service.remind(VISIT, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 dòng");

        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void failureAfterTheFlagPropagatesUntouchedSoTheTransactionRollsBack() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        IllegalStateException failure = new IllegalStateException("outbox lỗi");
        doThrow(failure).when(notifications).enqueue(any());

        assertThatThrownBy(() -> service.remind(VISIT, WINDOW)).isSameAs(failure);
    }

    @Test
    void taskCreationFailureAfterTheFlagPropagatesUntouched() {
        due(pet(null), contact(null, null, "0901234567"), null);
        IllegalStateException failure = new IllegalStateException("create lỗi");
        doThrow(failure).when(careTasks).create(any());

        assertThatThrownBy(() -> service.remind(VISIT, WINDOW)).isSameAs(failure);
    }

    @Test
    void readOnlyTransactionRejectedBeforeAnyRead() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.remind(VISIT, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnly");

        verifyNoInteractions(records, pets, notifications, careTasks);
    }

    @Test
    void remindIsDefaultTransactionWithoutNoRollbackFor() throws Exception {
        Method remind = FollowUpReminderService.class.getMethod("remind", long.class, DueDateWindow.class);
        Transactional tx = remind.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    @Test
    void findDueIdsIsReadOnly() throws Exception {
        Method find = FollowUpReminderService.class.getMethod("findDueIds", DueDateWindow.class, long.class,
                int.class);

        assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test
    void linkFitsTheLimit() {
        String link = FollowUpReminderService.LINK_FORMAT.formatted(Long.MAX_VALUE, Long.MAX_VALUE);

        assertThat(link.length()).isLessThanOrEqualTo(100);
    }
}
