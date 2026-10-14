package com.petcare.module.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.identity.entity.NotificationTemplate;

import jakarta.persistence.LockModeType;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {

    /**
     * Sửa / khôi phục mẫu (identity-v1 #34, #35; docs/adr/0037). Hibernate 6 trên PostgreSQL sinh
     * {@code FOR NO KEY UPDATE}. Phải là lần đầu dòng này được nạp trong persistence context: nếu đã nạp trước đó,
     * query trả lại instance cũ (docs/adr/0029 mục 4) và {@code before} của audit sai.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM NotificationTemplate t WHERE t.code = :code")
    Optional<NotificationTemplate> findByCodeForUpdate(@Param("code") String code);
}
