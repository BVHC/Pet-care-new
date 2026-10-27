package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.DueDose;

/**
 * ST18 nhánh tái chủng — sinh Care Task {@code VACCINE_OVERDUE} cho mũi quá ngày tái chủng
 * {@code reminder.vaccine_overdue_days} [CFG] ngày mà chưa được tiêm lại (BR-TB-04; docs/adr/0083; command
 * {@code raiseVaccineOverdueTask}, không đổi trạng thái nên không có dòng trong 03). Chỉ
 * {@code visit/job/VaccineOverdueJob} gọi.
 * <p>
 * Mỗi {@link #raise} là một transaction cho một mũi tiêm (convention 07 §7.4), READ COMMITTED (bắt buộc cho
 * {@code ON CONFLICT} của {@code CareTaskApi.create}), không {@code noRollbackFor}, không {@code try/catch}. Thứ tự khóa
 * giống hệt ST04: {@code pets FOR SHARE} → dòng mũi {@code FOR NO KEY UPDATE SKIP LOCKED} → UPDATE cờ
 * {@code overdue_handled_at} (phải đúng 1 dòng) → Care Task. Cờ và task cùng transaction: task lỗi thì cờ rollback và
 * lượt sau làm lại; đã commit thì cờ chặn xét lại (mỗi mũi tối đa 1 task). Mọi lý do khiến task vô dụng (thú mất, không
 * SĐT) được loại <b>trước</b> khi tạo task, vì sau commit cờ không cho xét lại. Không audit (BR-QT-15), không FSM.
 */
@Service
public class VaccineOverdueService {

    private static final Logger log = LoggerFactory.getLogger(VaccineOverdueService.class);

    private final VaccinationRepository doses;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final CareTaskApi careTasks;
    private final SystemConfigApi configs;
    private final Clock clock;

    public VaccineOverdueService(VaccinationRepository doses, PetQueryApi pets, CustomerQueryApi customers,
            CareTaskApi careTasks, SystemConfigApi configs, Clock clock) {
        this.doses = doses;
        this.pets = pets;
        this.customers = customers;
        this.careTasks = careTasks;
        this.configs = configs;
        this.clock = clock;
    }

    /**
     * Mũi có {@code next_due_date <= cutoff} là quá hạn: {@code cutoff = hôm nay − N}, N = [CFG]
     * {@code reminder.vaccine_overdue_days} đọc lúc chạy, một lần mỗi lượt (ADR-0057).
     */
    public LocalDate cutoff() {
        return LocalDate.now(clock).minusDays(configs.getInt(ConfigKey.REMINDER_VACCINE_OVERDUE_DAYS));
    }

    /** Tối đa {@code limit} id mũi quá hạn chưa xét; {@code afterId} = id cuối của lô trước, {@code 0} = lô đầu. Không khóa. */
    @Transactional(readOnly = true)
    public List<Long> findOverdueIds(LocalDate cutoff, long afterId, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        return afterId == 0 ? doses.findOverdueIds(cutoff, limit) : doses.findOverdueIdsAfter(cutoff, afterId, limit);
    }

    @Transactional
    public ReminderResult raise(long doseId, LocalDate cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("raise cần transaction ghi, không được readOnly");
        }
        Long petId = doses.findPetIdOfDose(doseId).orElse(null);
        if (petId == null) {
            return ReminderResult.SKIPPED;
        }
        PetSummary pet = pets.findPetForShare(petId).orElseThrow(() -> new IllegalStateException(
                "Mũi tiêm #" + doseId + " trỏ tới thú không tồn tại: " + petId));
        if (doses.lockOverdue(doseId, cutoff).isEmpty()) {
            return ReminderResult.SKIPPED;
        }
        DueDose dose = doses.findDueDose(doseId).orElseThrow(() -> new IllegalStateException(
                "Mũi tiêm #" + doseId + " đã khóa nhưng không đọc được"));
        if (dose.getNextDueDate().isAfter(cutoff)) {
            throw new IllegalStateException("Mũi tiêm #" + doseId + " chưa quá hạn nhưng đã được chọn");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (pet.deceased()) {
            markHandled(doseId, now);
            log.warn("VACCINE_OVERDUE_PET_DECEASED vaccinationId={} petId={}", doseId, petId);
            return ReminderResult.NOT_SENT;
        }
        CustomerContact contact = customers.findContact(pet.customerId()).orElseThrow(() -> new IllegalStateException(
                "Thú #" + petId + " trỏ tới hồ sơ khách không tồn tại: " + pet.customerId()));

        markHandled(doseId, now);
        if (contact.phone() == null || contact.phone().isBlank()) {
            log.info("VACCINE_OVERDUE_NO_PHONE vaccinationId={} customerId={}", doseId, pet.customerId());
            return ReminderResult.NOT_SENT;
        }
        careTasks.create(new NewCareTask(CareTaskType.VACCINE_OVERDUE, doseId, dose.getBranchId(), pet.customerId(),
                petId, dose.getNextDueDate()));
        return ReminderResult.TASK_CREATED;
    }

    private void markHandled(long doseId, Instant now) {
        int updated = doses.markOverdueHandled(doseId, now);
        if (updated != 1) {
            throw new IllegalStateException("Mũi tiêm #" + doseId + " đã khóa nhưng đánh dấu xét quá hạn được " + updated
                    + " dòng");
        }
    }
}
