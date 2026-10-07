package com.petcare.module.identity.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.module.identity.api.Role;
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
 * Bảng {@code accounts} (erd §1, ROOT · SM #1). Tài khoản đăng nhập của khách và nhân viên; email là định danh duy
 * nhất (BR-TK-01). Khóa ({@code is_locked}) độc lập với {@code status} (BR-QT-11, 12). Đã có: đăng ký
 * (Tài khoản#1), xác thực OTP (#2), xóa tài khoản {@code PENDING} quá hạn (#3, ST02 — xóa cứng bằng SQL ở
 * {@code AccountRepository}); các chuyển trạng thái khác (vô hiệu hóa, khóa…) làm ở các task TK/QT sau.
 */
@Getter
@Entity
@Table(name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "is_locked", nullable = false)
    private boolean locked;

    @Column(name = "locked_reason")
    private String lockedReason;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "first_failed_login_at")
    private Instant firstFailedLoginAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_settings", nullable = false)
    private Map<String, Object> notificationSettings = new HashMap<>();

    /**
     * Hạn xác thực của tài khoản {@code PENDING}, chốt lúc đăng ký (BR-TK-08, BR-QT-13); quá hạn thì ST02 xóa
     * (docs/adr/0013). Có giá trị ⇔ {@code PENDING} (CHECK {@code ck_accounts_pending_expiry}).
     */
    @Column(name = "pending_expires_at")
    private Instant pendingExpiresAt;

    /**
     * Tài khoản#1 — đăng ký: tài khoản khách {@code PENDING}. Không lưu SĐT: SĐT của khách nằm ở hồ sơ khách
     * (BR-TK-01, CHECK {@code ck_accounts_phone_by_role}). {@code email} đã chuẩn hóa chữ thường.
     * {@code pendingExpiresAt} = thời điểm đăng ký + {@code account.pending_ttl_hours} [CFG].
     */
    public static Account registerCustomer(String email, String passwordHash, Instant pendingExpiresAt) {
        Account account = new Account();
        account.email = email;
        account.passwordHash = passwordHash;
        account.role = Role.CUSTOMER;
        account.status = AccountStatus.PENDING;
        account.pendingExpiresAt = pendingExpiresAt;
        return account;
    }

    /**
     * Tài khoản#2 — xác thực OTP: {@code PENDING → ACTIVE}, bỏ hạn xác thực. Service đã gọi
     * {@code validateTransition} trước.
     */
    public void verify() {
        this.status = AccountStatus.ACTIVE;
        this.pendingExpiresAt = null;
    }
}
