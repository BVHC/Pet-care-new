package com.petcare.module.workforce.service;

import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.ShiftWeeklySummaryResponse;
import com.petcare.module.workforce.mapper.ShiftWeeklySummaryMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.StaffShiftCount;
import com.petcare.platform.security.BranchScope;

/**
 * Thống kê số ca mỗi nhân viên trong tuần cho BRANCH_MANAGER (UC34, workforce-v1 #16; BR-NS-03, BR-NS-12; docs/adr/0149
 * mục "Thống kê tuần").
 * <p>
 * Chỉ đọc: {@code readOnly} + REPEATABLE_READ để danh sách nhân viên, số đếm và định mức [CFG] cùng một ảnh chụp; không
 * khóa, không ghi, không audit, không outbox (BR-NS-10 không liệt kê đọc; thống kê chỉ để theo dõi nên lệch một thao tác
 * song song là chấp nhận được). Chi nhánh luôn là của người gọi (không có tham số chi nhánh). Thứ tự: {@code requireBranch}
 * (403) → {@code requireWeekStart} (400, trước mọi truy vấn và mọi phép cộng ngày) → nhân viên → số đếm → định mức.
 */
@Service
public class ShiftWeeklySummaryService {

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final ShiftAssignmentRepository assignments;
    private final ShiftWeeklySummaryMapper mapper;
    private final SystemConfigApi configs;

    public ShiftWeeklySummaryService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftAssignmentRepository assignments, ShiftWeeklySummaryMapper mapper, SystemConfigApi configs) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.assignments = assignments;
        this.mapper = mapper;
        this.configs = configs;
    }

    /**
     * workforce-v1 #16 — mọi nhân viên A06–A08 {@code ACTIVE} và không bị khóa của chi nhánh người gọi (kể cả người chưa
     * có ca), sắp {@code staffName} (so sánh tiếng Việt) rồi {@code staffId}. {@code shiftCount} đếm phân ca
     * {@code SCHEDULED} của tuần, cả hai nguồn, mọi chi nhánh (khớp #17).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ShiftWeeklySummaryResponse> listWeeklySummaries(LocalDate weekStart) {
        Long branchId = ShiftAssignmentService.requireBranch(branchScope.current().branchId());
        ShiftRegistrationService.requireWeekStart(weekStart);

        // identity trả từng chức vụ và xếp người online trước, nên gộp ba danh sách rồi sắp lại theo tên ở đây.
        List<StaffSummary> staff = new ArrayList<>();
        for (Role role : ShiftAssignmentService.SCHEDULABLE_ROLES) {
            staff.addAll(staffDirectory.findAssignableStaff(branchId, role));
        }
        if (staff.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> counts = new HashMap<>();
        for (StaffShiftCount row : assignments.countScheduledInWeek(
                staff.stream().map(StaffSummary::accountId).toList(), weekStart, weekStart.plusDays(6))) {
            counts.put(row.getStaffId(), Math.toIntExact(row.getShiftCount()));
        }
        int required = configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);

        Collator names = Collator.getInstance(Locale.forLanguageTag("vi-VN"));
        return staff.stream()
                .map(member -> mapper.toResponse(member, counts.getOrDefault(member.accountId(), 0), required))
                .sorted(Comparator.comparing(ShiftWeeklySummaryResponse::staffName, names)
                        .thenComparing(ShiftWeeklySummaryResponse::staffId))
                .toList();
    }
}
