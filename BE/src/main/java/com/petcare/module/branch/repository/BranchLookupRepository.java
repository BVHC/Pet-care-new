package com.petcare.module.branch.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.petcare.module.branch.entity.Branch;

/**
 * Câu đọc cho các method của {@code BranchQueryApi} thuộc UC33 (bật / tắt dịch vụ) và UC42 (quota) mà task đó chưa
 * làm entity. Native để không tạo entity thừa; task UC33 / UC42 thay bằng entity của chúng khi thêm endpoint ghi.
 */
public interface BranchLookupRepository extends Repository<Branch, Long> {

    /** Chưa có dòng {@code branch_services} nghĩa là chưa bật (giả định bảo thủ, erd §2 không nói rõ). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM branch_services WHERE branch_id = :branchId "
            + "AND service_id = :serviceId AND is_enabled)", nativeQuery = true)
    boolean isServiceEnabled(@Param("branchId") Long branchId, @Param("serviceId") Long serviceId);

    @Query(value = "SELECT bs.branch_id FROM branch_services bs JOIN branches b ON b.id = bs.branch_id "
            + "WHERE bs.service_id = :serviceId AND bs.is_enabled AND b.status = 'ACTIVE' ORDER BY bs.branch_id",
            nativeQuery = true)
    List<Long> findActiveBranchIdsEnablingService(@Param("serviceId") Long serviceId);

    @Query(value = "SELECT CAST(quota AS INTEGER) FROM slot_quotas WHERE branch_id = :branchId "
            + "AND service_group = :serviceGroup AND slot_date = :slotDate AND slot_start = :slotStart",
            nativeQuery = true)
    Optional<Integer> findSlotQuota(@Param("branchId") Long branchId, @Param("serviceGroup") String serviceGroup,
            @Param("slotDate") LocalDate slotDate, @Param("slotStart") LocalTime slotStart);

    @Query(value = "SELECT CAST(default_quota AS INTEGER) FROM branch_quota_defaults WHERE branch_id = :branchId "
            + "AND service_group = :serviceGroup", nativeQuery = true)
    Optional<Integer> findDefaultQuota(@Param("branchId") Long branchId, @Param("serviceGroup") String serviceGroup);
}
