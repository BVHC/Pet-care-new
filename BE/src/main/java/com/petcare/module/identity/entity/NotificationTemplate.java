package com.petcare.module.identity.entity;

import java.util.ArrayList;
import java.util.List;

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
 * migration theo 06 §8 Q4; ứng dụng không thêm hay xóa. Đọc qua {@code NotificationTemplateQueryApi}; sửa nội dung và
 * khôi phục mặc định (UC10) làm ở task QT sau.
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
}
