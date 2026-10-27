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
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.DueDose;

/**
 * {@link VaccineReminderService} (docs/adr/0082): khóa {@code pets} → khóa dòng → đọc → cờ → đầu ra; mọi nhánh bỏ qua
 * không ghi gì; lỗi sau cờ ném nguyên vẹn (rollback cùng cờ); ranh giới transaction.
 */
class VaccineReminderServiceTest {

    private static final long DOSE = 801L;
    private static final long PET = 9L;
    private static final long CUSTOMER = 7L;
    private static final long BRANCH = 3L;
    private static final long TYPE = 11L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);
    private static final DueDateWindow WINDOW = DueDateWindow.at(TODAY, 7);
    /** {@code now} sau khóa, có nano để kiểm cắt về µs. */
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:10.123456789Z");
    private static final Instant AT = NOW.truncatedTo(ChronoUnit.MICROS);

    private final VaccinationRepository doses = mock(VaccinationRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final AccountContactApi accounts = mock(AccountContactApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final CareTaskApi careTasks = mock(CareTaskApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final VaccineReminderService service = new VaccineReminderService(doses, pets, customers, accounts,
            branches, catalog, notifications, careTasks, configs, Clock.fixed(NOW, ZoneOffset.UTC));

    record Dose(Long getId, Long getPetId, Long getBranchId, Long getVaccineTypeId, LocalDate getNextDueDate)
            implements DueDose {}

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

    /** Dựng toàn bộ đường đi tới bước đầu ra với thú sống. */
    private void due(PetSummary pet, CustomerContact contact, AccountContact account) {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet));
        when(doses.lockDueForReminder(DOSE, TODAY, WINDOW.to())).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, DUE)));
        when(doses.markDueReminded(DOSE, AT)).thenReturn(1);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact));
        if (contact.accountId() != null) {
            when(accounts.findContacts(List.of(contact.accountId()))).thenReturn(List.of(account));
        }
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh Q1", "1 Lê Lợi", "0281234567", BranchStatus.ACTIVE, false)));
        when(catalog.findVaccineType(TYPE)).thenReturn(Optional.of(
                new VaccineTypeInfo(TYPE, "Dại", Species.CAT, true)));
    }

    // ---- cửa sổ, quét

    @Test
    void windowIsTodayThroughConfiguredDays() {
        when(configs.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE)).thenReturn(7);

        assertThat(service.window()).isEqualTo(WINDOW);
        verify(configs).getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE);
    }

    @Test
    void findDueIdsPicksFirstOrNextBatchQuery() {
        when(doses.findDueForReminderIds(TODAY, WINDOW.to(), 50)).thenReturn(List.of(1L));
        when(doses.findDueForReminderIdsAfter(TODAY, WINDOW.to(), 1L, 50)).thenReturn(List.of());

        assertThat(service.findDueIds(WINDOW, 0, 50)).containsExactly(1L);
        assertThat(service.findDueIds(WINDOW, 1L, 50)).isEmpty();
    }

    // ---- remind

    @Test
    void remindsInOrderShareLockRowLockReadMarkThenEnqueue() {
        due(pet(null), contact(null, 5L, "0901234567"), new AccountContact(5L, "a@x.vn", false));

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.REMINDED);

        InOrder order = inOrder(doses, pets, notifications);
        order.verify(doses).findPetIdOfDose(DOSE);
        order.verify(pets).findPetForShare(PET);
        order.verify(doses).lockDueForReminder(DOSE, TODAY, WINDOW.to());
        order.verify(doses).findDueDose(DOSE);
        order.verify(doses).markDueReminded(DOSE, AT);
        order.verify(notifications, org.mockito.Mockito.times(2)).enqueue(any());
        verify(doses, never()).save(any());
        verify(doses, never()).findById(anyLong());
        verifyNoInteractions(careTasks);
    }

    @Test
    void accountGetsInAppWithPrefilledLinkAndEmailWithPayloadOfTheTemplate() {
        due(pet(null), contact(null, 5L, null), new AccountContact(5L, "a@x.vn", false));

        service.remind(DOSE, WINDOW);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, org.mockito.Mockito.times(2)).enqueue(sent.capture());
        NotificationRequest app = sent.getAllValues().get(0);
        NotificationRequest email = sent.getAllValues().get(1);
        assertThat(app.templateCode()).isEqualTo("VACCINE_REMINDER_APP");
        assertThat(app.channel()).isEqualTo(Channel.IN_APP);
        assertThat(app.recipientAccountId()).isEqualTo(5L);
        assertThat(app.linkUrl()).isEqualTo("/booking?petId=9&branchId=3&serviceType=VACCINE");
        assertThat(email.templateCode()).isEqualTo("VACCINE_REMINDER");
        assertThat(email.recipientEmail()).isEqualTo("a@x.vn");
        assertThat(email.linkUrl()).isNull();
        assertThat(app.payload()).containsEntry("ten_khach", "Nguyễn Văn A").containsEntry("ten_thu_cung", "Mi")
                .containsEntry("loai_vaccine", "Dại").containsEntry("ngay_tai_chung", "03/11/2026")
                .containsEntry("chi_nhanh", "Chi nhánh Q1").containsEntry("so_dien_thoai_chi_nhanh", "0281234567");
    }

    @Test
    void counterProfileWithEmailGetsOnlyEmail() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.REMINDED);

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(sent.capture());
        assertThat(sent.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.getValue().recipientEmail()).isEqualTo("c@x.vn");
        verifyNoInteractions(careTasks);
    }

    @Test
    void profileWithoutEmailCreatesDueTaskForTheBranchOfTheDoseAndTheCurrentOwner() {
        due(pet(null), contact(null, null, "0901234567"), null);

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.TASK_CREATED);

        InOrder order = inOrder(doses, careTasks);
        order.verify(doses).markDueReminded(DOSE, AT);
        order.verify(careTasks).create(new NewCareTask(CareTaskType.VACCINE_DUE, DOSE, BRANCH, CUSTOMER, PET, DUE));
        verifyNoInteractions(notifications);
    }

    @Test
    void profileWithoutEmailAndPhoneIsMarkedButNothingIsSent() {
        due(pet(null), contact(null, null, " "), null);

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);

        verify(doses).markDueReminded(DOSE, AT);
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void deceasedPetIsMarkedWithoutLookupOrOutput() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(TODAY.minusDays(1))));
        when(doses.lockDueForReminder(DOSE, TODAY, WINDOW.to())).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, DUE)));
        when(doses.markDueReminded(DOSE, AT)).thenReturn(1);

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.NOT_SENT);

        verifyNoInteractions(customers, accounts, branches, catalog, notifications, careTasks);
    }

    @Test
    void unknownDoseIsSkippedBeforeAnyLock() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.empty());

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verifyNoInteractions(pets, notifications, careTasks);
        verify(doses, never()).lockDueForReminder(anyLong(), any(), any());
    }

    @Test
    void lockMissIsSkippedWithoutAnyWrite() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null)));
        when(doses.lockDueForReminder(DOSE, TODAY, WINDOW.to())).thenReturn(Optional.empty());

        assertThat(service.remind(DOSE, WINDOW)).isEqualTo(ReminderResult.SKIPPED);

        verify(doses, never()).findDueDose(anyLong());
        verify(doses, never()).markDueReminded(anyLong(), any());
        verifyNoInteractions(customers, notifications, careTasks);
    }

    @Test
    void missingPetIsDataCorruptionAndWritesNothing() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class);

        verify(doses, never()).markDueReminded(anyLong(), any());
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void doseOutsideTheWindowAfterTheLockIsAnError() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null)));
        when(doses.lockDueForReminder(DOSE, TODAY, WINDOW.to())).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, TODAY.minusDays(1))));

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ngoài cửa sổ");

        verify(doses, never()).markDueReminded(anyLong(), any());
    }

    @Test
    void missingBranchRollsBackBeforeTheFlagIsSet() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class);

        verify(doses, never()).markDueReminded(anyLong(), any());
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void missingRequiredVariableFailsBeforeTheFlagIsSet() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(
                new BranchSummary(BRANCH, "Chi nhánh Q1", "1 Lê Lợi", null, BranchStatus.ACTIVE, false)));

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("so_dien_thoai_chi_nhanh");

        verify(doses, never()).markDueReminded(anyLong(), any());
        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void flagNotHittingExactlyOneRowIsAnErrorAndSendsNothing() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        when(doses.markDueReminded(DOSE, AT)).thenReturn(0);

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 dòng");

        verifyNoInteractions(notifications, careTasks);
    }

    @Test
    void failureAfterTheFlagPropagatesUntouchedSoTheTransactionRollsBack() {
        due(pet(null), contact("c@x.vn", null, "0901234567"), null);
        IllegalStateException failure = new IllegalStateException("outbox lỗi");
        doThrow(failure).when(notifications).enqueue(any());

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isSameAs(failure);
    }

    @Test
    void taskCreationFailureAfterTheFlagPropagatesUntouched() {
        due(pet(null), contact(null, null, "0901234567"), null);
        IllegalStateException failure = new IllegalStateException("create lỗi");
        doThrow(failure).when(careTasks).create(any());

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isSameAs(failure);
    }

    @Test
    void readOnlyTransactionRejectedBeforeAnyRead() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.remind(DOSE, WINDOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnly");

        verifyNoInteractions(doses, pets, notifications, careTasks);
    }

    // ---- ranh giới transaction

    @Test
    void remindIsDefaultTransactionWithoutNoRollbackFor() throws Exception {
        Method remind = VaccineReminderService.class.getMethod("remind", long.class, DueDateWindow.class);
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
        Method find = VaccineReminderService.class.getMethod("findDueIds", DueDateWindow.class, long.class, int.class);

        assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test
    void linkAndPayloadFitTheLimits() {
        String link = VaccineReminderService.LINK_FORMAT.formatted(Long.MAX_VALUE, Long.MAX_VALUE);

        assertThat(link.length()).isLessThanOrEqualTo(100);
        assertThat(link).startsWith("/booking?petId=").endsWith("&serviceType=VACCINE");
    }
}
