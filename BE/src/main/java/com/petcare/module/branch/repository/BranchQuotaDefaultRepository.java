package com.petcare.module.branch.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.branch.entity.BranchQuotaDefault;
import com.petcare.module.branch.entity.BranchQuotaDefaultId;

public interface BranchQuotaDefaultRepository extends JpaRepository<BranchQuotaDefault, BranchQuotaDefaultId> {

    @Query("SELECT q FROM BranchQuotaDefault q WHERE q.id.branchId = :branchId")
    List<BranchQuotaDefault> findByBranchId(@Param("branchId") Long branchId);
}
