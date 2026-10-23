package com.petcare.module.branch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.branch.entity.BranchServiceSetting;
import com.petcare.module.branch.entity.BranchServiceSettingId;

public interface BranchServiceSettingRepository extends JpaRepository<BranchServiceSetting, BranchServiceSettingId> {

    @Query("SELECT s FROM BranchServiceSetting s WHERE s.id.branchId = :branchId")
    List<BranchServiceSetting> findByBranchId(@Param("branchId") Long branchId);

    /** Id các chi nhánh {@code ACTIVE} đang bật dịch vụ, tăng dần (trang dịch vụ công khai, BR-CK-01). */
    @Query("SELECT s.id.branchId FROM BranchServiceSetting s, Branch b WHERE b.id = s.id.branchId "
            + "AND b.status = com.petcare.module.branch.entity.BranchStatus.ACTIVE "
            + "AND s.id.serviceId = :serviceId AND s.enabled = true ORDER BY s.id.branchId")
    List<Long> findActiveBranchIdsEnabling(@Param("serviceId") Long serviceId);

    /** Cặp {@code [serviceId, branchId]} của chi nhánh {@code ACTIVE} đang bật các dịch vụ, theo dịch vụ rồi chi nhánh tăng dần. */
    @Query("SELECT s.id.serviceId, s.id.branchId FROM BranchServiceSetting s, Branch b WHERE b.id = s.id.branchId "
            + "AND b.status = com.petcare.module.branch.entity.BranchStatus.ACTIVE "
            + "AND s.id.serviceId IN :serviceIds AND s.enabled = true ORDER BY s.id.serviceId, s.id.branchId")
    List<Object[]> findActiveBranchPairsEnabling(@Param("serviceIds") java.util.Collection<Long> serviceIds);
}
