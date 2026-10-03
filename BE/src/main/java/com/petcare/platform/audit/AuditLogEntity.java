package com.petcare.platform.audit;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.petcare.platform.model.CreatedAtEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code audit_logs} (docs/05-erd.md §1, LOG). Chỉ thêm mới (BR-QT-16): entity {@link Immutable}, không setter,
 * DB còn trigger chặn UPDATE/DELETE/TRUNCATE. {@code actorAccountId} cố ý không map quan hệ vì cột không có FK
 * (erd §13 mục 9). Chỉ {@link AuditRecorder} tạo bản ghi.
 */
@Getter
@Entity
@Immutable
@Table(name = "audit_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLogEntity extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_account_id")
    private Long actorAccountId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data")
    private JsonNode beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data")
    private JsonNode afterData;

    @Column(name = "reason")
    private String reason;

    @Column(name = "ip_address")
    private String ipAddress;

    AuditLogEntity(Long actorAccountId, String actorEmail, String action, String entityType, Long entityId,
            JsonNode beforeData, JsonNode afterData, String reason, String ipAddress) {
        this.actorAccountId = actorAccountId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.reason = reason;
        this.ipAddress = ipAddress;
    }
}
