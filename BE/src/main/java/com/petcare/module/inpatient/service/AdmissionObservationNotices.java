package com.petcare.module.inpatient.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.platform.config.TimeConfig;

/**
 * Thông báo ngay khi mục theo dõi nội trú đánh dấu bất thường (BR-LT-16; docs/adr/0149 mục 32). Chỉ ghi outbox trong
 * transaction ghi mục theo dõi ({@code NotificationApi.enqueue} là {@code MANDATORY}): lỗi thì mục theo dõi cũng không
 * được lưu, và ST20 không bao giờ gửi thư về một mục không tồn tại.
 * <ul>
 * <li><b>Khách</b> theo BR-TB-02: tài khoản không {@code PENDING} → IN_APP + EMAIL tới email tài khoản; hồ sơ tại quầy có
 * email → EMAIL; không kênh nào → log, chi nhánh liên hệ theo màn hình. Người nhận là chủ <i>hiện tại</i> của thú.</li>
 * <li><b>VET phụ trách</b> (người đang được gán Visit) nhận một bản IN_APP, <b>trừ khi chính họ là người ghi</b>.
 * Đọc người phụ trách không khóa {@code visits}: chịu cùng cửa sổ hẹp của D080.</li>
 * </ul>
 * Bản sao quy tắc kênh của {@code boarding.service.BoardingRecipients} (package-private; module không import
 * {@code service} của module khác), cùng khuôn {@code visit/ReminderChannels}, {@code appointment/AppointmentNoticeTargets}.
 */
@Component
class AdmissionObservationNotices {

    /** Khách chưa có màn hình xem nội trú (09 Q4): đưa tới danh sách thú cưng. */
    static final String CUSTOMER_LINK = "/pets";
    /** Màn hình nội trú của nhân viên (FE {@code /admin/inpatient}). */
    static final String STAFF_LINK = "/admin/inpatient";
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<String> REQUIRED_VARS = List.of("ten_thu_cung", "chi_nhanh", "ma_noi_tru", "ngay_ghi");
    private static final Logger log = LoggerFactory.getLogger(AdmissionObservationNotices.class);

    private final NotificationApi notifications;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchQueryApi branches;
    private final AccountContactApi accounts;
    private final VisitQueryApi visits;

    AdmissionObservationNotices(NotificationApi notifications, CustomerQueryApi customers, PetQueryApi pets,
            BranchQueryApi branches, AccountContactApi accounts, VisitQueryApi visits) {
        this.notifications = notifications;
        this.customers = customers;
        this.pets = pets;
        this.branches = branches;
        this.accounts = accounts;
        this.visits = visits;
    }

    /** {@code recordedAt} là {@code created_at} đã lưu; {@code recorderId} là người ghi mục theo dõi. */
    void enqueue(Admission admission, Instant recordedAt, Long recorderId) {
        PetSummary pet = pets.findPet(admission.getPetId()).orElseThrow(() -> corrupt("thú", admission));
        CustomerContact contact = customers.findContact(pet.customerId())
                .orElseThrow(() -> corrupt("hồ sơ khách", admission));
        String branchName = branches.findBranch(admission.getBranchId()).orElseThrow(() -> corrupt("chi nhánh", admission))
                .name();
        VisitSummary visit = visits.findVisit(admission.getVisitId()).orElseThrow(() -> corrupt("lượt khám", admission));
        Map<String, Object> payload = payload(contact.fullName(), pet.name(), branchName, admission, recordedAt);

        AccountContact account = contact.accountId() == null ? null : accounts.findContacts(List.of(contact.accountId()))
                .stream().findFirst().orElseThrow(() -> corrupt("tài khoản khách", admission));
        List<NotificationRequest> requests = new ArrayList<>(customerRequests(contact, account, payload));
        if (requests.isEmpty()) {
            log.info("INPATIENT_OBSERVATION_ABNORMAL_NO_CHANNEL admissionId={} customerId={}", admission.getId(),
                    contact.customerId());
        }
        Long assigneeId = visit.assigneeId();
        if (assigneeId != null && !assigneeId.equals(recorderId)) {
            requests.add(new NotificationRequest(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL_APP,
                    Channel.IN_APP, assigneeId, null, payload, STAFF_LINK));
        }
        requests.forEach(notifications::enqueue);
    }

    /** Biến của mẫu (V62); mọi biến bắt buộc khác {@code null} (rollback thay vì để ST20 đánh dấu {@code FAILED}). */
    static Map<String, Object> payload(String customerName, String petName, String branchName, Admission admission,
            Instant recordedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_khach", customerName);
        payload.put("ten_thu_cung", petName);
        payload.put("chi_nhanh", branchName);
        payload.put("ma_noi_tru", admission.getCode());
        payload.put("ngay_ghi", DATE_FORMAT.format(recordedAt.atZone(TimeConfig.BUSINESS_ZONE)));
        for (String name : REQUIRED_VARS) {
            if (payload.get(name) == null) {
                throw new IllegalStateException("Thiếu biến bắt buộc " + name + " cho thông báo theo dõi nội trú bất thường "
                        + admission.getId());
            }
        }
        return payload;
    }

    /** Kênh và người nhận của khách theo BR-TB-02; {@code account} null khi hồ sơ không gắn tài khoản. */
    static List<NotificationRequest> customerRequests(CustomerContact contact, AccountContact account,
            Map<String, Object> payload) {
        List<NotificationRequest> requests = new ArrayList<>(2);
        if (account != null && !account.pending()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL_APP,
                    Channel.IN_APP, account.accountId(), null, payload, CUSTOMER_LINK));
            requests.add(new NotificationRequest(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL, Channel.EMAIL,
                    null, account.email(), payload, null));
        } else if (contact.email() != null && !contact.email().isBlank()) {
            requests.add(new NotificationRequest(NotificationTemplateCode.INPATIENT_OBSERVATION_ABNORMAL, Channel.EMAIL,
                    null, contact.email(), payload, null));
        }
        return requests;
    }

    private static IllegalStateException corrupt(String what, Admission admission) {
        return new IllegalStateException("Thiếu " + what + " của ca nội trú #" + admission.getId());
    }
}
