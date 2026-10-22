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

    /** Tài khoản#1: hồ sơ online gắn tài khoản (BR-KH-01). Họ tên bắt buộc, SĐT và email có thể null. */
    public static Customer createOnline(Long accountId, String fullName, String phone, String email) {
        Customer customer = new Customer();
        customer.accountId = accountId;
        customer.fullName = fullName;
        customer.phone = phone;
        customer.email = email;
        customer.createdChannel = CustomerChannel.ONLINE;
        return customer;
    }

    /** UC22: hồ sơ tại quầy, chưa gắn tài khoản; bắt buộc họ tên và SĐT (BR-KH-01, CHECK {@code ck_customers_counter_phone}). */
    public static Customer createAtCounter(String fullName, String phone, String email, Long createdBy) {
        if (phone == null) {
            throw new BusinessRuleViolationException("BR-KH-01", "Hồ sơ tại quầy bắt buộc có số điện thoại");
        }
        Customer customer = new Customer();
        customer.fullName = fullName;
        customer.phone = phone;
        customer.email = email;
        customer.createdChannel = CustomerChannel.COUNTER;
        customer.createdBy = createdBy;
        return customer;
    }

    /**
     * UC22 — lễ tân sửa hồ sơ. Nhận <b>giá trị cuối</b> của ba trường (service đã quyết giữ / xóa / đổi và đã chặn sửa
     * email của hồ sơ có tài khoản, BR-TK-16). Hồ sơ tại quầy không được xóa SĐT (BR-KH-01).
     */
    public void updateByStaff(String newFullName, String newPhone, String newEmail) {
        if (newFullName == null || newFullName.isBlank()) {
            throw new IllegalArgumentException("full_name is required");
        }
        if (createdChannel == CustomerChannel.COUNTER && newPhone == null) {
            throw new BusinessRuleViolationException("BR-KH-01", "Hồ sơ tại quầy bắt buộc có số điện thoại");
        }
        this.fullName = newFullName;
        this.phone = newPhone;
        this.email = newEmail;
    }

    /** BR-TK-19: bật cờ chờ quyết định liên kết (chỉ hồ sơ online, CHECK {@code ck_customers_link_pending_online}). */
    public void flagLinkDecision() {
        if (createdChannel != CustomerChannel.ONLINE) {
            throw new IllegalStateException("Only an online profile can wait for a link decision");
        }
        this.linkDecisionPending = true;
    }

    /** BR-TK-19 (b): "Không phải tôi". */
    public void clearLinkDecision() {
        this.linkDecisionPending = false;
    }

    /**
     * BR-TK-19 (a): hồ sơ tại quầy nhận tài khoản; email theo tài khoản, SĐT giữ nguyên. Caller đã xóa hồ sơ online giữ
     * {@code account_id} này và {@code flush()} ({@code uq_customers_account_id} không deferrable).
     */
    public void linkAccount(Long newAccountId, String accountEmail) {
        this.accountId = newAccountId;
        this.email = accountEmail;
    }

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
