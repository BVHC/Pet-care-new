package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.DueDose;

/**
 * ST04 — nhắc tái chủng trước {@code reminder.vaccine_days_before} [CFG] ngày (BR-TB-01, BR-TB-02; docs/adr/0082;
 * command {@code remindRevaccination}, không đổi trạng thái nên không có dòng trong 03). Chỉ
 * {@code visit/job/VaccineReminderJob} gọi.
 * <p>
 * Mỗi {@link #remind} là một transaction cho một mũi tiêm (convention 07 §7.4), READ COMMITTED (bắt buộc cho
 * {@code ON CONFLICT} của {@code CareTaskApi.create}), không {@code noRollbackFor}, không {@code try/catch}. Thứ tự khóa:
 * {@code pets FOR SHARE} (nghĩa vụ ADR-0079 mục 2: trước dòng nguồn) → dòng mũi {@code FOR NO KEY UPDATE SKIP LOCKED}
 * (ST04 không bao giờ chờ khóa dòng nên không nằm trong vòng deadlock) → đọc projection dưới khóa → {@code UPDATE} có
 * điều kiện {@code due_reminded_at} (phải đúng 1 dòng) → ghi outbox hoặc Care Task. Đặt cờ cùng transaction với đầu ra
 * là điều kiện để không nhắc trùng (ADR-0012 §Chống gửi trùng); đầu ra lỗi thì rollback cả cờ và lượt sau nhắc lại.
 * Không audit (BR-QT-15 không liệt kê nhắc), không FSM.
 */
@Service
public class VaccineReminderService {

    /** Link bản trong app: form đặt lịch điền sẵn thú, chi nhánh và dịch vụ tiêm (BR-TB-01). FE đọc query (D051). */
    static final String LINK_FORMAT = "/booking?petId=%d&branchId=%d&serviceType=VACCINE";

    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "loai_vaccine", "ngay_tai_chung", "chi_nhanh",
            "so_dien_thoai_chi_nhanh");

    private static final Logger log = LoggerFactory.getLogger(VaccineReminderService.class);

    private final VaccinationRepository doses;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final AccountContactApi accounts;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final NotificationApi notifications;
    private final CareTaskApi careTasks;
    private final SystemConfigApi configs;
    private final Clock clock;

    public VaccineReminderService(VaccinationRepository doses, PetQueryApi pets, CustomerQueryApi customers,
            AccountContactApi accounts, BranchQueryApi branches, CatalogQueryApi catalog,
            NotificationApi notifications, CareTaskApi careTasks, SystemConfigApi configs, Clock clock) {
        this.doses = doses;
        this.pets = pets;
        this.customers = customers;
        this.accounts = accounts;
        this.branches = branches;
        this.catalog = catalog;
        this.notifications = notifications;
        this.careTasks = careTasks;
        this.configs = configs;
        this.clock = clock;
    }

    /** Cửa sổ nhắc hôm nay; [CFG] đọc lúc chạy, một lần mỗi lượt (ADR-0057). */
    public DueDateWindow window() {
        return DueDateWindow.at(LocalDate.now(clock), configs.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE));
    }

    /** Tối đa {@code limit} id mũi đến hạn nhắc; {@code afterId} = id cuối của lô trước, {@code 0} = lô đầu. Không khóa. */
    @Transactional(readOnly = true)
    public List<Long> findDueIds(DueDateWindow window, long afterId, int limit) {
        Objects.requireNonNull(window, "window");
        return afterId == 0
                ? doses.findDueForReminderIds(window.from(), window.to(), limit)
                : doses.findDueForReminderIdsAfter(window.from(), window.to(), afterId, limit);
    }

    @Transactional
    public ReminderResult remind(long doseId, DueDateWindow window) {
        Objects.requireNonNull(window, "window");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("remind cần transaction ghi, không được readOnly");
        }
        Long petId = doses.findPetIdOfDose(doseId).orElse(null);
        if (petId == null) {
            return ReminderResult.SKIPPED;
        }
        PetSummary pet = pets.findPetForShare(petId).orElseThrow(() -> new IllegalStateException(
                "Mũi tiêm #" + doseId + " trỏ tới thú không tồn tại: " + petId));
        if (doses.lockDueForReminder(doseId, window.from(), window.to()).isEmpty()) {
            return ReminderResult.SKIPPED;
        }
        DueDose dose = doses.findDueDose(doseId).orElseThrow(() -> new IllegalStateException(
                "Mũi tiêm #" + doseId + " đã khóa nhưng không đọc được"));
        if (!window.contains(dose.getNextDueDate())) {
            throw new IllegalStateException("Mũi tiêm #" + doseId + " ngoài cửa sổ nhắc nhưng đã được chọn");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (pet.deceased()) {
            markReminded(doseId, now);
            log.warn("VACCINE_REMINDER_PET_DECEASED vaccinationId={} petId={}", doseId, petId);
            return ReminderResult.NOT_SENT;
        }
        CustomerContact contact = customers.findContact(pet.customerId()).orElseThrow(() -> new IllegalStateException(
                "Thú #" + petId + " trỏ tới hồ sơ khách không tồn tại: " + pet.customerId()));
        AccountContact account = contact.accountId() == null ? null : accounts.findContacts(List.of(contact.accountId()))
                .stream().filter(a -> a.accountId().equals(contact.accountId())).findFirst().orElseThrow(
                        () -> new IllegalStateException("Hồ sơ khách #" + contact.customerId()
                                + " trỏ tới tài khoản không tồn tại: " + contact.accountId()));
        BranchSummary branch = branches.findBranch(dose.getBranchId()).orElseThrow(() -> new IllegalStateException(
                "Mũi tiêm #" + doseId + " trỏ tới chi nhánh không tồn tại: " + dose.getBranchId()));
        VaccineTypeInfo type = catalog.findVaccineType(dose.getVaccineTypeId()).orElseThrow(
                () -> new IllegalStateException("Mũi tiêm #" + doseId + " trỏ tới loại vaccine không tồn tại: "
                        + dose.getVaccineTypeId()));

        Map<String, Object> payload = payload(contact, pet, type, branch, dose.getNextDueDate(), doseId);
        List<NotificationRequest> requests = ReminderChannels.requests(contact, account, payload,
                NotificationTemplateCode.VACCINE_REMINDER, NotificationTemplateCode.VACCINE_REMINDER_APP,
                LINK_FORMAT.formatted(petId, dose.getBranchId()));

        markReminded(doseId, now);
        if (!requests.isEmpty()) {
            requests.forEach(notifications::enqueue);
            return ReminderResult.REMINDED;
        }
        if (contact.phone() != null && !contact.phone().isBlank()) {
            careTasks.create(new NewCareTask(CareTaskType.VACCINE_DUE, doseId, dose.getBranchId(), pet.customerId(),
                    petId, dose.getNextDueDate()));
            return ReminderResult.TASK_CREATED;
        }
        log.info("VACCINE_REMINDER_NO_CHANNEL vaccinationId={} customerId={}", doseId, pet.customerId());
        return ReminderResult.NOT_SENT;
    }

    private void markReminded(long doseId, Instant now) {
        int updated = doses.markDueReminded(doseId, now);
        if (updated != 1) {
            throw new IllegalStateException("Mũi tiêm #" + doseId + " đã khóa nhưng đánh dấu nhắc được " + updated
                    + " dòng");
        }
    }

    /** Biến của mẫu V22; {@code ten_khach} không bắt buộc, còn lại phải khác {@code null} ({@code enqueue} không kiểm). */
    static Map<String, Object> payload(CustomerContact contact, PetSummary pet, VaccineTypeInfo type,
            BranchSummary branch, LocalDate nextDueDate, long doseId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ten_thu_cung", pet.name());
        payload.put("loai_vaccine", type.name());
        payload.put("ngay_tai_chung", DATE_FORMAT.format(nextDueDate));
        payload.put("chi_nhanh", branch.name());
        payload.put("so_dien_thoai_chi_nhanh", branch.phone());
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho nhắc tái chủng của mũi #" + doseId);
            }
        }
        return payload;
    }
}
