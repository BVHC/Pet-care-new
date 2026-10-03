package com.petcare.module.branch.api;

import com.petcare.module.catalog.api.ServiceGroup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** Owner: branch (CN) · BE-2. Ngày giờ hiểu theo giờ Việt Nam (Asia/Ho_Chi_Minh). */
public interface BranchQueryApi {

    enum BranchStatus { DRAFT, ACTIVE }

    record BranchSummary(Long branchId, String name, String address, String phone,
                         BranchStatus status, boolean acceptsAfterHoursEmergency) {}

    record TimeRange(LocalTime open, LocalTime close) {}

    Optional<BranchSummary> findBranch(Long branchId);

    /** Chi nhánh ACTIVE cho trang công khai (BR-CK-01). */
    List<BranchSummary> listActiveBranches();

    /**
     * 0–2 khoảng giờ mở cửa áp dụng cho ngày (bản ghi hiệu lực lớn nhất ≤ ngày, BR-CN-02, 04).
     * Rỗng nếu là ngày nghỉ cố định hoặc ngày nghỉ (BR-CN-03).
     */
    List<TimeRange> openingRangesOn(Long branchId, LocalDate date);

    boolean isHoliday(Long branchId, LocalDate date);

    /** Đang trong một khoảng giờ mở cửa (Visit#1, Ca thu ngân#1, BR-LT-04). */
    boolean isOpenAt(Long branchId, LocalDateTime at);

    /** Giờ bắt đầu khoảng mở cửa kế tiếp sau {@code after} (ST13 tự chốt ca ngoài giờ). */
    Optional<LocalDateTime> nextOpeningStart(Long branchId, LocalDateTime after);

    /** Dịch vụ (kể cả loại chuồng) đang bật tại chi nhánh (BR-LH-01, BR-SP-04). */
    boolean isServiceEnabled(Long branchId, Long serviceId);

    /** Chi nhánh ACTIVE đang bật dịch vụ — trang dịch vụ công khai (BR-CK-01). */
    List<Long> findActiveBranchIdsEnablingService(Long serviceId);

    /** Quota khung: SlotQuota → BranchQuotaDefault → 1 (BR-LH-03). */
    int quotaFor(Long branchId, ServiceGroup group, LocalDate slotDate, LocalTime slotStart);
}
