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
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.DueDose;

/**
 * {@link VaccineOverdueService} (docs/adr/0083): khóa {@code pets} → khóa dòng → đọc → cờ → Care Task; mọi nhánh bỏ qua
 * không ghi gì; lỗi sau cờ ném nguyên vẹn (rollback cùng cờ); ranh giới transaction.
 */
class VaccineOverdueServiceTest {

    private static final long DOSE = 801L;
    private static final long PET = 9L;
    private static final long CUSTOMER = 7L;
    private static final long BRANCH = 3L;
    private static final long TYPE = 11L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final LocalDate CUTOFF = TODAY.minusDays(7);
    private static final LocalDate DUE = CUTOFF.minusDays(2);
    /** {@code now} sau khóa, có nano để kiểm cắt về µs. */
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:10.123456789Z");
    private static final Instant AT = NOW.truncatedTo(ChronoUnit.MICROS);

    private final VaccinationRepository doses = mock(VaccinationRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final CareTaskApi careTasks = mock(CareTaskApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final VaccineOverdueService service = new VaccineOverdueService(doses, pets, customers, careTasks, configs,
            Clock.fixed(NOW, ZoneOffset.UTC));

    record Dose(Long getId, Long getPetId, Long getBranchId, Long getVaccineTypeId, LocalDate getNextDueDate)
            implements DueDose {}

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static PetSummary pet(LocalDate deceasedOn) {
        return new PetSummary(PET, CUSTOMER, "Mi", Species.CAT, null, deceasedOn, null);
    }

    private static CustomerContact contact(String phone) {
        return new CustomerContact(CUSTOMER, "Nguyễn Văn A", phone, null, null, false);
    }

    /** Dựng đường đi tới bước ghi với mũi đã khóa được và cờ đặt được đúng 1 dòng. */
    private void overdue(PetSummary pet, CustomerContact contact) {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet));
        when(doses.lockOverdue(DOSE, CUTOFF)).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, DUE)));
        when(doses.markOverdueHandled(DOSE, AT)).thenReturn(1);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.ofNullable(contact));
    }

    // ---- ngưỡng, quét

    @Test
    void cutoffIsTodayMinusTheConfiguredDays() {
        when(configs.getInt(ConfigKey.REMINDER_VACCINE_OVERDUE_DAYS)).thenReturn(7);

        assertThat(service.cutoff()).isEqualTo(CUTOFF);
        verify(configs).getInt(ConfigKey.REMINDER_VACCINE_OVERDUE_DAYS);
    }

    @Test
    void findOverdueIdsPicksFirstOrNextBatchQuery() {
        when(doses.findOverdueIds(CUTOFF, 50)).thenReturn(List.of(1L));
        when(doses.findOverdueIdsAfter(CUTOFF, 1L, 50)).thenReturn(List.of());

        assertThat(service.findOverdueIds(CUTOFF, 0, 50)).containsExactly(1L);
        assertThat(service.findOverdueIds(CUTOFF, 1L, 50)).isEmpty();
    }

    // ---- raise

    @Test
    void raisesInOrderShareLockRowLockReadContactMarkThenCreateTheTask() {
        overdue(pet(null), contact("0901234567"));

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.TASK_CREATED);

        InOrder order = inOrder(doses, pets, customers, careTasks);
        order.verify(doses).findPetIdOfDose(DOSE);
        order.verify(pets).findPetForShare(PET);
        order.verify(doses).lockOverdue(DOSE, CUTOFF);
        order.verify(doses).findDueDose(DOSE);
        order.verify(customers).findContact(CUSTOMER);
        order.verify(doses).markOverdueHandled(DOSE, AT);
        order.verify(careTasks).create(new NewCareTask(CareTaskType.VACCINE_OVERDUE, DOSE, BRANCH, CUSTOMER, PET, DUE));
        verify(doses, never()).save(any());
        verify(doses, never()).findById(anyLong());
    }

    @Test
    void taskGoesToTheBranchOfTheDoseTheCurrentOwnerAndTheOriginalDueDate() {
        overdue(pet(null), contact("0901234567"));

        service.raise(DOSE, CUTOFF);

        verify(careTasks).create(new NewCareTask(CareTaskType.VACCINE_OVERDUE, DOSE, BRANCH, CUSTOMER, PET, DUE));
    }

    @Test
    void profileWithoutPhoneIsMarkedButNoTaskIsCreated() {
        overdue(pet(null), contact(null));

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.NOT_SENT);

        verify(doses).markOverdueHandled(DOSE, AT);
        verifyNoInteractions(careTasks);
    }

    @Test
    void blankPhoneCountsAsNoPhone() {
        overdue(pet(null), contact("  "));

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.NOT_SENT);

        verifyNoInteractions(careTasks);
    }

    @Test
    void deceasedPetIsMarkedWithoutContactLookupOrTask() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(TODAY.minusDays(1))));
        when(doses.lockOverdue(DOSE, CUTOFF)).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, DUE)));
        when(doses.markOverdueHandled(DOSE, AT)).thenReturn(1);

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.NOT_SENT);

        verifyNoInteractions(customers, careTasks);
    }

    @Test
    void unknownDoseIsSkippedBeforeAnyLock() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.empty());

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.SKIPPED);

        verifyNoInteractions(pets, customers, careTasks);
        verify(doses, never()).lockOverdue(anyLong(), any());
    }

    @Test
    void lockMissIsSkippedWithoutAnyWrite() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null)));
        when(doses.lockOverdue(DOSE, CUTOFF)).thenReturn(Optional.empty());

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.SKIPPED);

        verify(doses, never()).findDueDose(anyLong());
        verify(doses, never()).markOverdueHandled(anyLong(), any());
        verifyNoInteractions(customers, careTasks);
    }

    @Test
    void missingPetIsDataCorruptionAndWritesNothing() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isInstanceOf(IllegalStateException.class);

        verify(doses, never()).markOverdueHandled(anyLong(), any());
        verifyNoInteractions(customers, careTasks);
    }

    @Test
    void missingContactWritesNothing() {
        overdue(pet(null), null);

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isInstanceOf(IllegalStateException.class);

        verify(doses, never()).markOverdueHandled(anyLong(), any());
        verifyNoInteractions(careTasks);
    }

    @Test
    void doseNotYetOverdueAfterTheLockIsAnError() {
        when(doses.findPetIdOfDose(DOSE)).thenReturn(Optional.of(PET));
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(pet(null)));
        when(doses.lockOverdue(DOSE, CUTOFF)).thenReturn(Optional.of(DOSE));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, CUTOFF.plusDays(1))));

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chưa quá hạn");

        verify(doses, never()).markOverdueHandled(anyLong(), any());
    }

    @Test
    void doseDueExactlyOnTheCutoffIsOverdue() {
        overdue(pet(null), contact("0901234567"));
        when(doses.findDueDose(DOSE)).thenReturn(Optional.of(new Dose(DOSE, PET, BRANCH, TYPE, CUTOFF)));

        assertThat(service.raise(DOSE, CUTOFF)).isEqualTo(ReminderResult.TASK_CREATED);

        verify(careTasks).create(new NewCareTask(CareTaskType.VACCINE_OVERDUE, DOSE, BRANCH, CUSTOMER, PET, CUTOFF));
    }

    @Test
    void flagNotHittingExactlyOneRowIsAnErrorAndCreatesNoTask() {
        overdue(pet(null), contact("0901234567"));
        when(doses.markOverdueHandled(DOSE, AT)).thenReturn(0);

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 dòng");

        verifyNoInteractions(careTasks);
    }

    @Test
    void taskCreationFailureAfterTheFlagPropagatesUntouchedSoTheTransactionRollsBack() {
        overdue(pet(null), contact("0901234567"));
        IllegalStateException failure = new IllegalStateException("create lỗi");
        doThrow(failure).when(careTasks).create(any());

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isSameAs(failure);
    }

    @Test
    void readOnlyTransactionRejectedBeforeAnyRead() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.raise(DOSE, CUTOFF)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnly");

        verifyNoInteractions(doses, pets, customers, careTasks);
    }

    // ---- ranh giới transaction

    @Test
    void raiseIsDefaultTransactionWithoutNoRollbackFor() throws Exception {
        Method raise = VaccineOverdueService.class.getMethod("raise", long.class, LocalDate.class);
        Transactional tx = raise.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    @Test
    void findOverdueIdsIsReadOnly() throws Exception {
        Method find = VaccineOverdueService.class.getMethod("findOverdueIds", LocalDate.class, long.class, int.class);

        assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
    }
}
