package com.petcare.module.workforce.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.mapper.ShiftRegistrationMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * Đăng ký ca theo tuần của nhân viên (UC35, workforce-v1 #15–17; BR-NS-11, BR-NS-12; docs/adr/0138 mục "Đăng ký tuần —
 * đọc #17").
 * <p>
 * <b>#17 đọc</b> — {@code readOnly} + REPEATABLE_READ để danh sách, tên ca / nhân viên và {@code pendingRequestId} cùng một
 * ảnh chụp; không ghi, không audit, không outbox (BR-NS-10 không liệt kê đọc). {@code staffId} luôn là người gọi (không có
 * tham số định danh), mọi chi nhánh (BR-NS-03). Thứ tự: {@link #requireWeekStart} (400, trước mọi truy vấn và mọi phép
 * cộng ngày) → phân ca {@code SCHEDULED} của tuần → ghép tên theo lô → định mức [CFG] → hạn sửa.
 */
@Service
public class ShiftRegistrationService {

    /** Năm tối thiểu / tối đa của {@code weekStart}: chặn tràn {@code plusDays} và "date out of range" ở PostgreSQL (500). */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    private final BranchScope branchScope;
    private final ShiftAssignmentRepository assignments;
    private final ShiftAssignmentService shiftAssignments;
    private final ShiftRegistrationMapper mapper;
    private final SystemConfigApi configs;

    public ShiftRegistrationService(BranchScope branchScope, ShiftAssignmentRepository assignments,
            ShiftAssignmentService shiftAssignments, ShiftRegistrationMapper mapper, SystemConfigApi configs) {
        this.branchScope = branchScope;
        this.assignments = assignments;
        this.shiftAssignments = shiftAssignments;
        this.mapper = mapper;
        this.configs = configs;
    }

    /**
     * workforce-v1 #17 — đăng ký ca của <b>chính người gọi</b> trong tuần {@code weekStart}. Không kiểm hạn đăng ký: xem
     * được cả tuần đã qua hạn ({@code editableUntil} chỉ là thông tin cho client).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShiftRegistrationResponse getMyShiftRegistration(LocalDate weekStart) {
        Long staffId = branchScope.current().accountId();
        requireWeekStart(weekStart);
        List<ShiftAssignment> rows = assignments.findScheduledInWeek(staffId, weekStart, weekStart.plusDays(6));
        List<ShiftAssignmentResponse> list = shiftAssignments.toResponses(rows);
        return mapper.toResponse(weekStart, configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS),
                editableUntil(weekStart), list);
    }

    /**
     * BR-NS-11: {@code weekStart} là thứ Hai. Rồi giới hạn năm (giả định của docs/adr/0138, không có trong docs/02): năm
     * ngoài {@code [2000, 2100]} cũng là 400 {@code BR-NS-11} thay vì để {@code plusDays(6)} tràn hoặc PostgreSQL báo
     * date out of range (500). Dùng chung cho #15 và #16.
     */
    static void requireWeekStart(LocalDate weekStart) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessRuleViolationException("BR-NS-11", "Ngày bắt đầu tuần phải là thứ Hai");
        }
        if (weekStart.getYear() < MIN_YEAR || weekStart.getYear() > MAX_YEAR) {
            throw new BusinessRuleViolationException("BR-NS-11",
                    "Chỉ hỗ trợ tuần từ năm " + MIN_YEAR + " đến năm " + MAX_YEAR);
        }
    }

    /** Hạn sửa đăng ký tuần: 00:00 thứ Hai của {@code weekStart} giờ Việt Nam (BR-NS-11); không phụ thuộc đồng hồ. */
    static Instant editableUntil(LocalDate weekStart) {
        return weekStart.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
