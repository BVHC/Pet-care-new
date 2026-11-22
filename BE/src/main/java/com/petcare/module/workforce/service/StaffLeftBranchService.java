package com.petcare.module.workforce.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.CancellableAssignment;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * Nhân viên rời chi nhánh (BR-NS-09; 03 Phân ca#6 và Yêu cầu#5; docs/adr/0138 bước 5, trả D071). Gọi từ
 * {@code StaffLeftBranchListener} <b>trong transaction của identity</b> (vô hiệu hóa / điều chuyển), nên {@code MANDATORY}
 * và không đặt {@code isolation} (READ COMMITTED: câu chọn khóa phải thấy dòng đã commit sau khi chờ khóa).
 * <p>
 * Thứ tự cố định: đọc và khóa phân ca {@code FOR NO KEY UPDATE} (một câu, {@code id} tăng dần) → UPDATE phân ca → UPDATE
 * yêu cầu {@code PENDING} → thông báo. {@code accounts} do identity đã giữ nên không khóa lại; thứ tự khóa vẫn
 * {@code accounts → phân ca → yêu cầu} (ADR-0123 mục 8). Không có kiểm quyền theo người gọi và <b>không dùng
 * {@code BranchScope}</b>: người điều chuyển là SUPER_MANAGER (không có chi nhánh), còn phạm vi dữ liệu do sự kiện
 * quyết định ({@code staffId}, chi nhánh cũ). Không bao giờ ném lỗi nghiệp vụ: không có phân ca thì không có gì để làm
 * (BR-NS-09 khác BR-QT-08, không chặn thao tác gốc). Không audit riêng ({@code STAFF_DISABLED} / {@code STAFF_TRANSFERRED}
 * của identity đã ghi), không {@code catch}: lỗi nào cũng rút cả thao tác gốc.
 */
@Service
public class StaffLeftBranchService {

    /** Lý do của phân ca (≤ 300) và ghi chú của yêu cầu bị đóng; sự kiện không phân biệt vô hiệu hóa với điều chuyển. */
    static final String LEFT_BRANCH_REASON = "Nhân viên không còn làm việc tại chi nhánh";

    private final ShiftAssignmentRepository assignments;
    private final ShiftRequestRepository requests;
    private final StaffDirectoryApi staffDirectory;
    private final BranchQueryApi branches;
    private final NotificationApi notifications;
    private final Clock clock;

    public StaffLeftBranchService(ShiftAssignmentRepository assignments, ShiftRequestRepository requests,
            StaffDirectoryApi staffDirectory, BranchQueryApi branches, NotificationApi notifications, Clock clock) {
        this.assignments = assignments;
        this.requests = requests;
        this.staffDirectory = staffDirectory;
        this.branches = branches;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * Hủy mọi phân ca {@code SCHEDULED} chưa bắt đầu của {@code staffId} tại {@code branchId} (chi nhánh <b>cũ</b>), đóng
     * yêu cầu {@code PENDING} của chúng, thông báo nhân viên mỗi ca một cặp (BR-NS-08).
     *
     * @return số phân ca đã hủy (0 = không có gì để làm)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelUpcomingShiftsOf(Long staffId, Long branchId) {
        requireNonNull(staffId, "staffId");
        requireNonNull(branchId, "branchId");

        Instant now = clock.instant();
        LocalDateTime localNow = LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        List<CancellableAssignment> rows = assignments.lockNotStartedScheduled(staffId, branchId,
                localNow.toLocalDate(), localNow);
        if (rows.isEmpty()) {
            return 0;
        }
        List<Long> ids = rows.stream().map(CancellableAssignment::getAssignmentId).toList();

        int cancelled = assignments.cancelByStaffLeaving(ids, LEFT_BRANCH_REASON, now);
        if (cancelled != ids.size()) {
            // Các dòng đã khóa ngay trước đó nên không thể lệch; lệch là hỏng bất biến, rút cả thao tác gốc.
            throw new IllegalStateException("Hủy " + cancelled + "/" + ids.size() + " phân ca của nhân viên " + staffId);
        }
        requests.rejectPendingBySystem(ids, LEFT_BRANCH_REASON, now);

        // Tên chi nhánh là của chi nhánh CŨ (sự kiện), không phải StaffSummary.branchId: sau điều chuyển nó đã là chi nhánh mới.
        StaffSummary staff = staffDirectory.findStaff(staffId)
                .orElseThrow(() -> new IllegalStateException("Phân ca trỏ tới tài khoản không phải nhân viên " + staffId));
        String email = staffDirectory.findEmail(staffId).orElse(null);
        String branchName = branches.findBranch(branchId).map(BranchSummary::name)
                .orElseThrow(() -> new IllegalStateException("Phân ca thiếu chi nhánh " + branchId));
        for (CancellableAssignment row : rows) {
            ShiftNotices.cancelled(staffId, email, staff.fullName(), row.getShiftName(), row.getWorkDate(),
                    row.getStartTime(), row.getEndTime(), branchName, LEFT_BRANCH_REASON)
                    .forEach(notifications::enqueue);
        }
        return cancelled;
    }
}
