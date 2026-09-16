package com.petcare.module.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child entity của NotificationTask (Module 23) — nhật ký chuyển phát thật
 * qua gateway. docs/06-erd.md §3.7. Không extend BaseEntity (không có cột audit).
 */
@Entity
@Table(name = "notification_delivery_logs")
@Getter
@Setter
@NoArgsConstructor
public class NotificationDeliveryLog {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private NotificationTask task;

    @Column(name = "gateway_provider", nullable = false, length = 50)
    private String gatewayProvider;

    @Column(name = "gateway_message_id", length = 100)
    private String gatewayMessageId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    public NotificationDeliveryLog(NotificationTask task, String gatewayProvider, String status, String responsePayload) {
        this.task = task;
        this.gatewayProvider = gatewayProvider;
        this.status = status;
        this.responsePayload = responsePayload;
    }

    @PrePersist
    void prePersist() {
        if (deliveredAt == null) {
            deliveredAt = LocalDateTime.now();
        }
    }
}
