package com.petcare.module.identity.entity;

import java.time.Duration;
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
 * {@code AccountRepository}), bộ đếm đăng nhập sai và khóa tạm (BR-TK-09, ST01 — trường phụ, không phải trạng thái), đổi mật khẩu (BR-TK-14),
 * đặt lại mật khẩu (BR-TK-13);
 * các chuyển trạng thái khác (vô hiệu hóa, khóa…) làm ở các task TK/QT sau.
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

    /**
     * BR-TK-09 (ST01): đang khóa tạm khi {@code now < locked_until}; đúng mốc {@code locked_until} là đã hết khóa
     * (docs/adr/0019 mục 2).
     */
    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * BR-TK-09: một lần sai mật khẩu, cửa sổ cố định tính từ lần sai đầu (docs/adr/0019 mục 2). Hết cửa sổ
     * ({@code now − first ≥ window}) thì bắt đầu cửa sổ mới. Chạm {@code maxAttempts} thì khóa tạm tới
     * {@code now + lockDuration} (chốt vào dòng, BR-QT-13) và xóa bộ đếm. Caller không gọi khi đang khóa tạm (lần sai
     * lúc khóa không đếm). Dùng chung cho đăng nhập và đổi mật khẩu (BR-TK-14).
     *
     * @return {@code true} nếu lần sai này vừa kích hoạt khóa tạm
     */
    public boolean recordFailedLogin(Instant now, Duration window, int maxAttempts, Duration lockDuration) {
        if (firstFailedLoginAt == null || !now.isBefore(firstFailedLoginAt.plus(window))) {
            failedLoginCount = 1;
            firstFailedLoginAt = now;
        } else {
            failedLoginCount++;
        }
        if (failedLoginCount < maxAttempts) {
            return false;
        }
        lockedUntil = now.plus(lockDuration);
        failedLoginCount = 0;
        firstFailedLoginAt = null;
        return true;
    }

    /**
     * UC05 — đổi mật khẩu thành công (BR-TK-14, 17; docs/adr/0022): hash mới, gỡ bắt đổi mật khẩu lần đầu, xóa bộ đếm
     * sai như đăng nhập thành công. Caller đã kiểm {@link #isTemporarilyLocked} dưới khóa dòng, nên {@code locked_until}
     * còn lại (nếu có) đã hết hạn và bị xóa. Không đổi {@code status}: không phải chuyển trạng thái của SM #1.
     */
    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        recordSuccessfulLogin(now);
    }

    /**
     * UC04 — đặt lại mật khẩu bằng OTP (BR-TK-13; docs/adr/0023): hash mới, gỡ bắt đổi mật khẩu lần đầu (BR-TK-17,
     * docs/adr/0019 *Hệ quả*), xóa bộ đếm sai và <b>gỡ khóa tạm</b> kể cả khi còn hạn (BR-TK-13). Đặt
     * {@code last_seen_at = NULL} vì mọi phiên bị hủy cùng lúc — "đăng xuất thì chuyển offline ngay" (BR-TN-06), như
     * đăng xuất (docs/adr/0021). Caller giữ khóa dòng và hủy phiên sau lệnh này (thứ tự {@code accounts → sessions}).
     * Không đổi {@code status}: không phải chuyển trạng thái của SM #1.
     */
    public void resetPassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        this.failedLoginCount = 0;
        this.firstFailedLoginAt = null;
        this.lockedUntil = null;
        this.lastSeenAt = null;
    }

    /**
     * Đăng nhập thành công: xóa bộ đếm sai và khóa tạm đã hết hạn (docs/adr/0019 mục 2). Caller đã kiểm
     * {@link #isTemporarilyLocked} trước, nên {@code locked_until} còn lại (nếu có) luôn ≤ {@code now}.
     */
    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        firstFailedLoginAt = null;
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            lockedUntil = null;
        }
    }

    /**
     * UC06 — nhân viên tự sửa SĐT (BR-TK-15). Nhân viên luôn có SĐT, khách không lưu SĐT ở tài khoản (BR-TK-01, CHECK
     * {@code ck_accounts_phone_by_role}): gọi với tài khoản khách hoặc {@code null} là lỗi lập trình. Định dạng đã kiểm
     * ở request; không kiểm trùng (SĐT không duy nhất, v16). Caller giữ khóa dòng (docs/adr/0026).
     */
    public void changeStaffPhone(String newPhone) {
        if (role == Role.CUSTOMER) {
            throw new IllegalStateException("Customer account " + id + " has no account phone (BR-TK-01)");
        }
        if (newPhone == null) {
            throw new IllegalStateException("Staff account " + id + " must keep a phone (BR-TK-01)");
        }
        this.phone = newPhone;
    }
}
