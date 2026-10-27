package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository.DueFollowUp;

/**
 * ST04 — nhắc tái khám trước {@code reminder.follow_up_days_before} [CFG] ngày (BR-TB-06, BR-TB-02; docs/adr/0082;
 * command {@code remindFollowUp}). Chỉ {@code visit/job/FollowUpReminderJob} gọi. Cùng khuôn, cùng thứ tự khóa và cùng
 * bảo đảm retry với {@link VaccineReminderService}; khác ở nguồn ({@code medical_records}, khóa theo {@code visit_id}),
 * cờ ({@code follow_up_reminded_at}; thú đã mất đặt {@code follow_up_cancelled_at} đúng nghĩa "nhắc bị hủy", BR-TB-06) và
 * Care Task ({@code FOLLOW_UP_DUE}, {@code sourceId} = id lượt, chi nhánh của lượt có hẹn). Nhắc bị hủy khi thú đã đặt
 * lịch Khám / có Visit Khám mới do {@link FollowUpCancellationService} (docs/adr/0084), cùng khóa dòng bệnh án nên ST04
 * không gửi chen vào.
 */
@Service
public class FollowUpReminderService {

    /** Link bản trong app: form đặt lịch điền sẵn thú và chi nhánh, khách tự chọn dịch vụ (BR-TB-06). FE đọc query (D051). */
    static final String LINK_FORMAT = "/booking?petId=%d&branchId=%d";

    static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "ngay_tai_kham", "chi_nhanh",
            "so_dien_thoai_chi_nhanh");

    private static final Logger log = LoggerFactory.getLogger(FollowUpReminderService.class);

    private final MedicalRecordRepository records;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final AccountContactApi accounts;
    private final BranchQueryApi branches;
    private final NotificationApi notifications;
    private final CareTaskApi careTasks;
    private final SystemConfigApi configs;
    private final Clock clock;

    public FollowUpReminderService(MedicalRecordRepository records, PetQueryApi pets, CustomerQueryApi customers,
            AccountContactApi accounts, BranchQueryApi branches, NotificationApi notifications,
            CareTaskApi careTasks, SystemConfigApi configs, Clock clock) {
        this.records = records;
        this.pets = pets;
        this.customers = customers;
        this.accounts = accounts;
        this.branches = branches;
        this.notifications = notifications;
        this.careTasks = careTasks;
        this.configs = configs;
        this.clock = clock;
    }

    /** Cửa sổ nhắc hôm nay; [CFG] đọc lúc chạy, một lần mỗi lượt (ADR-0057). */
    public DueDateWindow window() {
        return DueDateWindow.at(LocalDate.now(clock), configs.getInt(ConfigKey.REMINDER_FOLLOW_UP_DAYS_BEFORE));
    }

    /** Tối đa {@code limit} id lượt có hẹn tái khám đến hạn nhắc; {@code afterId} = id cuối của lô trước, {@code 0} = lô đầu. */
    @Transactional(readOnly = true)
    public List<Long> findDueIds(DueDateWindow window, long afterId, int limit) {
        Objects.requireNonNull(window, "window");
        return afterId == 0
                ? records.findDueForReminderIds(window.from(), window.to(), limit)
                : records.findDueForReminderIdsAfter(window.from(), window.to(), afterId, limit);
    }

    @Transactional
    public ReminderResult remind(long visitId, DueDateWindow window) {
        Objects.requireNonNull(window, "window");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("remind cần transaction ghi, không được readOnly");
        }
        Long petId = records.findPetIdOfVisit(visitId).orElse(null);
        if (petId == null) {
            return ReminderResult.SKIPPED;
        }
        PetSummary pet = pets.findPetForShare(petId).orElseThrow(() -> new IllegalStateException(
                "Lượt #" + visitId + " trỏ tới thú không tồn tại: " + petId));
        if (records.lockDueForReminder(visitId, window.from(), window.to()).isEmpty()) {
            return ReminderResult.SKIPPED;
        }
        DueFollowUp due = records.findDueFollowUp(visitId).orElseThrow(() -> new IllegalStateException(
                "Bệnh án #" + visitId + " đã khóa nhưng không đọc được"));
        if (!window.contains(due.getFollowUpDate())) {
            throw new IllegalStateException("Bệnh án #" + visitId + " ngoài cửa sổ nhắc nhưng đã được chọn");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (pet.deceased()) {
            int updated = records.markFollowUpCancelled(visitId, now);
            requireOne(visitId, updated);
            log.warn("FOLLOW_UP_REMINDER_PET_DECEASED visitId={} petId={}", visitId, petId);
            return ReminderResult.NOT_SENT;
        }
        CustomerContact contact = customers.findContact(pet.customerId()).orElseThrow(() -> new IllegalStateException(
                "Thú #" + petId + " trỏ tới hồ sơ khách không tồn tại: " + pet.customerId()));
        AccountContact account = contact.accountId() == null ? null : accounts.findContacts(List.of(contact.accountId()))
                .stream().filter(a -> a.accountId().equals(contact.accountId())).findFirst().orElseThrow(
                        () -> new IllegalStateException("Hồ sơ khách #" + contact.customerId()
                                + " trỏ tới tài khoản không tồn tại: " + contact.accountId()));
        BranchSummary branch = branches.findBranch(due.getBranchId()).orElseThrow(() -> new IllegalStateException(
                "Lượt #" + visitId + " trỏ tới chi nhánh không tồn tại: " + due.getBranchId()));

        Map<String, Object> payload = payload(contact, pet, branch, due.getFollowUpDate(), visitId);
        List<NotificationRequest> requests = ReminderChannels.requests(contact, account, payload,
                NotificationTemplateCode.FOLLOW_UP_REMINDER, NotificationTemplateCode.FOLLOW_UP_REMINDER_APP,
                LINK_FORMAT.formatted(petId, due.getBranchId()));

        requireOne(visitId, records.markFollowUpReminded(visitId, now));
        if (!requests.isEmpty()) {
            requests.forEach(notifications::enqueue);
            return ReminderResult.REMINDED;
        }
        if (contact.phone() != null && !contact.phone().isBlank()) {
            careTasks.create(new NewCareTask(CareTaskType.FOLLOW_UP_DUE, visitId, due.getBranchId(), pet.customerId(),
                    petId, due.getFollowUpDate()));
            return ReminderResult.TASK_CREATED;
        }
        log.info("FOLLOW_UP_REMINDER_NO_CHANNEL visitId={} customerId={}", visitId, pet.customerId());
        return ReminderResult.NOT_SENT;
    }

    private static void requireOne(long visitId, int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Bệnh án #" + visitId + " đã khóa nhưng đánh dấu nhắc được " + updated
                    + " dòng");
        }
    }

    /** Biến của mẫu V22; {@code ten_khach} không bắt buộc, còn lại phải khác {@code null} ({@code enqueue} không kiểm). */
    static Map<String, Object> payload(CustomerContact contact, PetSummary pet, BranchSummary branch,
            LocalDate followUpDate, long visitId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", contact.fullName());
        payload.put("ten_thu_cung", pet.name());
        payload.put("ngay_tai_kham", VaccineReminderService.DATE_FORMAT.format(followUpDate));
        payload.put("chi_nhanh", branch.name());
        payload.put("so_dien_thoai_chi_nhanh", branch.phone());
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho nhắc tái khám của lượt #" + visitId);
            }
        }
        return payload;
    }
}
