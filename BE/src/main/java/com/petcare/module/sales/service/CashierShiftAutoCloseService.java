package com.petcare.module.sales.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.entity.CashierShift;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.fsm.CashierShiftTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ST13 — tự chốt ca thu ngân chưa chốt (Ca thu ngân#4 ca thường, #5 ca ngoài giờ; BR-TG-05; docs/adr/0064; cơ chế job:
 * docs/adr/0007). Mỗi ca một {@code @Transactional}: khóa {@code FOR NO KEY UPDATE SKIP LOCKED} (ca đang có phiếu thu
 * giữ {@code FOR SHARE} thì bỏ qua, lượt sau làm) → kiểm lại {@code OPEN} dưới khóa (đã nằm trong câu khóa) → hạn tính
 * <b>lúc chạy</b> từ giờ mở cửa hiện hành và [CFG] grace → tổng phiếu thu → {@code CLOSED}, {@code auto_closed},
 * không có số thực đếm → thông báo trong app cho BRANCH_MANAGER (không có ai: SUPER_MANAGER). Không audit (hệ thống,
 * không có actor; BR-QT-15 không liệt kê tự chốt). Không {@code catch}: lỗi thông báo làm rollback cả việc chốt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashierShiftAutoCloseService {

    /** Kết quả xử lý một ca. */
    public enum Result { CLOSED, NOT_DUE, SKIPPED, NO_DEADLINE }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final CashierShiftRepository shifts;
    private final BranchQueryApi branches;
    private final StaffDirectoryApi staffDirectory;
    private final SystemConfigApi configs;
    private final NotificationApi notifications;
    private final ShiftNoticeRecipients recipients;
    private final ShiftTotalsReader totals;
    private final CashierShiftTransitionHandler fsm;

    /** Keyset id các ca {@code OPEN}; đọc thuần, trước mọi khóa. */
    @Transactional(readOnly = true)
    public List<Long> findOpenIds(long lastId, int limit) {
        return shifts.findOpenIdsAfter(lastId, limit);
    }

    @Transactional
    public Result autoCloseCashierShift(Long shiftId, Instant now) {
        Optional<CashierShift> locked = shifts.lockOpenSkipLocked(shiftId);
        if (locked.isEmpty()) {
            return Result.SKIPPED;
        }
        CashierShift shift = locked.get();
        Optional<LocalDateTime> deadline = deadlineOf(shift);
        if (deadline.isEmpty()) {
            log.warn("CASHIER_SHIFT_NO_NEXT_OPENING shiftId={} branchId={}", shiftId, shift.getBranchId());
            return Result.NO_DEADLINE;
        }
        if (now.isBefore(deadline.get().atZone(TimeConfig.BUSINESS_ZONE).toInstant())) {
            return Result.NOT_DUE;
        }
        fsm.validateTransition(shift.getStatus(), CashierShiftStatus.CLOSED);
        Instant closedAt = now.truncatedTo(ChronoUnit.MICROS);
        shift.autoClose(totals.read(shiftId), closedAt);
        shifts.saveAndFlush(shift);
        notifyManagers(shift, closedAt);
        log.info("CASHIER_SHIFT_AUTO_CLOSED shiftId={} branchId={} afterHours={} expectedCash={} expectedTransfer={}",
                shiftId, shift.getBranchId(), shift.isAfterHours(), shift.getExpectedCash(),
                shift.getExpectedTransfer());
        return Result.CLOSED;
    }

    private Optional<LocalDateTime> deadlineOf(CashierShift shift) {
        if (shift.isAfterHours()) {
            // Ca ngoài giờ qua nửa đêm và thuộc ngày mở ca: hạn là lúc bắt đầu khoảng mở cửa kế tiếp sau khi mở ca.
            return branches.nextOpeningStart(shift.getBranchId(),
                    LocalDateTime.ofInstant(shift.getOpenedAt(), TimeConfig.BUSINESS_ZONE));
        }
        int grace = configs.getInt(ConfigKey.CASHIER_SHIFT_AUTO_CLOSE_GRACE_MINUTES);
        return Optional.of(ShiftDeadline.regular(shift.getBusinessDate(),
                branches.openingRangesOn(shift.getBranchId(), shift.getBusinessDate()), grace));
    }

    private void notifyManagers(CashierShift shift, Instant closedAt) {
        List<Long> targets = recipients.forBranch(shift.getBranchId());
        if (targets.isEmpty()) {
            log.warn("CASHIER_SHIFT_AUTO_CLOSE_NO_RECIPIENT shiftId={} branchId={}", shift.getId(),
                    shift.getBranchId());
            return;
        }
        String cashierName = staffDirectory.findStaff(shift.getCashierId())
                .orElseThrow(() -> new IllegalStateException("Ca #" + shift.getId() + " có lễ tân #"
                        + shift.getCashierId() + " không còn trong danh bạ nhân viên")).fullName();
        String branchName = branches.findBranch(shift.getBranchId())
                .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + shift.getBranchId()
                        + " không tồn tại")).name();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ma_ca", String.valueOf(shift.getId()));
        payload.put("loai_ca", shift.isAfterHours() ? "ngoài giờ" : "thường");
        payload.put("ten_thu_ngan", cashierName);
        payload.put("chi_nhanh", branchName);
        payload.put("ngay_ca", shift.getBusinessDate().format(DATE));
        payload.put("gio_chot", closedAt.atZone(TimeConfig.BUSINESS_ZONE).format(DATE_TIME));
        for (Long target : targets) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.CASHIER_SHIFT_AUTO_CLOSED_APP,
                    Channel.IN_APP, target, null, payload, null));
        }
    }
}
