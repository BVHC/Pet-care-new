package com.petcare.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ánh xạ bảng {@code audit_logs} (docs/06-erd.md §"Bảng: audit_logs", RULE-25-08 —
 * lưu trữ bất biến tối thiểu 5 năm; Module 25 CRUD/API chưa triển khai, entity này
 * chỉ phục vụ {@link AuditAspect} ghi — không có Controller/Service đọc lại).
 * Không extends {@link com.petcare.platform.model.BaseEntity} vì bảng chỉ INSERT,
 * không có updated_at/created_by/updated_by/deleted_at/version (append-only, giống
 * {@code OutboxEvent}).
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Account của actor thực hiện hành động (KHÔNG phải resource bị tác động). */
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Column(name = "client_ip", length = 50)
    private String clientIp;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_before", columnDefinition = "jsonb")
    private String snapshotBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_after", columnDefinition = "jsonb")
    private String snapshotAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
