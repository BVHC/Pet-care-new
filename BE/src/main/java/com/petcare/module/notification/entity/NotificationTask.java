package com.petcare.module.notification.entity;

import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.enums.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
 * Aggregate Root Module 23 (Notification) — bản tối giản, chỉ đủ cho
 * SendRegistrationOTP (Module 01 gọi qua NotificationService, RULE-23-01).
 * Không extend platform.model.BaseEntity (bảng notification_tasks không có
 * created_by/updated_by/deleted_at/version — docs/06-erd.md §3.7).
 */
@Entity
@Table(name = "notification_tasks")
@Getter
@Setter
@NoArgsConstructor
public class NotificationTask {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "channel", nullable = false)
    private NotificationChannel channel;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "title")
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public NotificationTask(UUID recipientUserId, NotificationChannel channel, String eventType, String content) {
        this.recipientUserId = recipientUserId;
        this.channel = channel;
        this.eventType = eventType;
        this.content = content;
        this.status = NotificationStatus.PENDING;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (scheduledAt == null) {
            scheduledAt = now;
        }
    }
}
