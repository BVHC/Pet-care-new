package com.petcare.module.branch.api;

import com.petcare.module.catalog.api.ServiceGroup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Như {@link #findActiveBranchIdsEnablingService} cho nhiều dịch vụ một lần (thêm 2026-10-23, T32): key đủ mọi id
     * truyền vào (danh sách rỗng nếu không chi nhánh ACTIVE nào bật), id chi nhánh tăng dần; tập rỗng → map rỗng.
     */
    Map<Long, List<Long>> findActiveBranchIdsEnablingServices(Collection<Long> serviceIds);

    /** Quota khung: SlotQuota → BranchQuotaDefault → 1 (BR-LH-03). */
    int quotaFor(Long branchId, ServiceGroup group, LocalDate slotDate, LocalTime slotStart);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 cho module ai (07 I-4). Placeholder T55c đã được thay bằng bản thật ở AI-0 (9/11/2026).
    // ------------------------------------------------------------------------------------------------------------

    /** I-4 (07 §11, UC90, UC92): chi nhánh {@code ACTIVE} nhận cấp cứu ngoài giờ (BR-CN-05), theo tên. */
    List<BranchSummary> findEmergencyBranches();

    /** I-4 (07 §11, UC92): dịch vụ (kể cả loại chuồng) đang bật ở ít nhất một chi nhánh {@code ACTIVE}. */
    Set<Long> findServiceIdsEnabledAnywhere();
}
