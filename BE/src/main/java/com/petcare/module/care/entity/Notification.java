package com.petcare.module.care.entity;

import java.time.Instant;

import com.petcare.platform.model.TimestampedEntity;

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
 * Bảng {@code notifications} (erd §11, ROOT): thông báo trong ứng dụng của một tài khoản (UC88). Worker ST20 tạo dòng
 * từ outbox kênh {@code IN_APP} (docs/adr/0014); đọc / đánh dấu đã đọc làm ở task UC88 sau. Độ dài tối đa khớp V1 —
 * worker kiểm trước khi INSERT để một dòng outbox lỗi không chặn cả luồng.
 */
@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends TimestampedEntity {

    public static final int MAX_TYPE_LENGTH = 40;
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_LINK_URL_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "link_url", updatable = false)
    private String linkUrl;

    @Column(name = "read_at")
    private Instant readAt;

    /** Thông báo mới, chưa đọc. Caller đã kiểm độ dài theo các hằng {@code MAX_*}. */
    public static Notification deliver(Long accountId, String type, String title, String body, String linkUrl) {
        Notification notification = new Notification();
        notification.accountId = accountId;
        notification.type = type;
        notification.title = title;
        notification.body = body;
        notification.linkUrl = linkUrl;
        return notification;
    }
}
