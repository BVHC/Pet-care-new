package com.petcare.module.identity.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code notification_templates} (erd §1, REF, PK chuỗi). Mẫu do hệ thống định sẵn (BR-QT-14), seed bằng
 * migration theo 06 §8 Q4; ứng dụng không thêm hay xóa. Đọc qua {@code NotificationTemplateQueryApi}. ADMIN chỉ sửa
 * được {@code subject}, {@code body} ({@link #changeContent}) và khôi phục bản mặc định ({@link #restoreDefault}) —
 * UC10, docs/adr/0037; {@code channel}, {@code allowed_vars}, {@code required_vars}, {@code default_*} không có lệnh
 * sửa nào.
 */
@Getter
@Entity
@Table(name = "notification_templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate extends TimestampedEntity {

    @Id
    @Column(name = "code")
    private String code;

    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "default_subject")
    private String defaultSubject;

    @Column(name = "default_body", nullable = false)
    private String defaultBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_vars", nullable = false)
    private List<String> allowedVars = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_vars", nullable = false)
    private List<String> requiredVars = new ArrayList<>();

    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * Sửa nội dung (identity-v1 #34). Lưu nguyên văn — biến đã được kiểm theo BR-QT-14 ở
     * {@code NotificationTemplateAdminService} (docs/adr/0037).
     */
    public void changeContent(String subject, String body, Long updatedBy) {
        this.subject = subject;
        this.body = body;
        this.updatedBy = updatedBy;
    }

    /** Khôi phục bản mặc định (identity-v1 #35, BR-QT-14). */
    public void restoreDefault(Long updatedBy) {
        changeContent(defaultSubject, defaultBody, updatedBy);
    }

    /** Nội dung đang dùng trùng từng ký tự với bản mặc định ({@code isDefault} của identity-v1). */
    public boolean isDefault() {
        return Objects.equals(subject, defaultSubject) && Objects.equals(body, defaultBody);
    }

    /** Nội dung đang dùng trùng từng ký tự với {@code subject}, {@code body} cho trước. */
    public boolean hasContent(String subject, String body) {
        return Objects.equals(this.subject, subject) && Objects.equals(this.body, body);
    }
}
