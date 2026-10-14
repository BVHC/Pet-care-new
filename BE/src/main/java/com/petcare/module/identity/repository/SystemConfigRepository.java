package com.petcare.module.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.SystemConfig;

import jakarta.persistence.LockModeType;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {

    /**
     * Sửa tham số (identity-v1 #32, docs/adr/0036 mục 4). Hibernate 6 trên PostgreSQL sinh {@code FOR NO KEY UPDATE}.
     * Phải là lần đầu dòng này được nạp trong persistence context: nếu đã nạp trước đó, query trả lại instance cũ
     * (docs/adr/0029 mục 4) và phép kiểm chéo dùng giá trị cũ. Nhiều key thì gọi theo key tăng dần.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM SystemConfig c WHERE c.configKey = :key")
    Optional<SystemConfig> findByKeyForUpdate(@Param("key") String key);
}
