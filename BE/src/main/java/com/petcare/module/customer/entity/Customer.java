package com.petcare.module.customer.entity;

import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.model.TimestampedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code customers} (erd §3, ROOT). Hồ sơ chủ thú cưng, dùng chung toàn chuỗi; tài khoản là phần gắn thêm qua
 * {@code account_id} (04 nguyên tắc 1, BR-KH-01). {@code accountId}, {@code createdBy} để dạng id, không map quan hệ sang
 * {@code accounts} vì bảng đó thuộc module identity (convention 01, 02 <i>Entity</i>).
 * <p>
 * Khung entity khai đủ mọi cột của V1 cho cả module customer: BE-1 tạo cho UC06 phía khách (docs/adr/0028), BE-2 mở rộng
 * cho T17 (UC22–26) — không tạo entity thứ hai cho cùng bảng (06 §6).
 */
@Getter
@Entity
@Table(name = "customers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_channel", nullable = false, updatable = false)
    private CustomerChannel createdChannel;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "link_decision_pending", nullable = false)
    private boolean linkDecisionPending;

    /**
     * UC06 — khách tự sửa hồ sơ (BR-TK-15, docs/adr/0028). Nhận <b>giá trị cuối</b> của cả ba trường: service đã quyết
     * giữ / xóa / đổi; email, nguồn, tài khoản và cờ chờ liên kết không đổi được ở đây. Hồ sơ tại quầy bắt buộc có SĐT
     * (BR-KH-01, CHECK {@code ck_customers_counter_phone}) — kiểm ở đây trước khi ghi để trả đúng mã rule thay vì 409.
     * Caller giữ khóa dòng {@code customers} từ trước khi đọc entity.
     */
    public void updateSelfProfile(String newFullName, String newPhone, String newAvatarUrl) {
        if (newFullName == null || newFullName.isBlank()) {
            throw new IllegalArgumentException("full_name is required");
        }
        if (createdChannel == CustomerChannel.COUNTER && newPhone == null) {
            throw new BusinessRuleViolationException("BR-KH-01", "Hồ sơ tại quầy bắt buộc có số điện thoại");
        }
        this.fullName = newFullName;
        this.phone = newPhone;
        this.avatarUrl = newAvatarUrl;
    }
}
