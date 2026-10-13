package com.petcare.module.identity.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 * đặt lại mật khẩu (BR-TK-13), tạo nhân viên (#4), đổi chức vụ (UC08), vô hiệu hóa (#5), kích hoạt lại (#6), sửa
 * email (BR-TK-16), khóa / mở khóa (#7, #8 — docs/adr/0035).
 */
@Getter
@Entity
@Table(name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends TimestampedEntity {

    /** A05–A08: nhóm chức vụ được đổi qua lại (BR-QT-05). */
    private static final Set<Role> BRANCH_STAFF_ROLES =
            EnumSet.of(Role.BRANCH_MANAGER, Role.RECEPTIONIST, Role.VET, Role.CARETAKER);

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
     * Tài khoản#4 — tạo tài khoản nhân viên (UC08; BR-QT-01, 02, BR-TK-01, 17; docs/adr/0030): {@code ACTIVE} ngay,
     * bắt buộc đổi mật khẩu ở lần đăng nhập đầu, có SĐT (CHECK {@code ck_accounts_phone_by_role}). {@code email} đã
     * chuẩn hóa chữ thường; {@code passwordHash} là BCrypt của mật khẩu tạm do hệ thống sinh. Service đã kiểm phân
     * cấp, BR-QT-03, BR-TK-01 và gọi {@code validateInitial(ACTIVE)} trước.
     */
    public static Account createStaff(String email, String phone, String passwordHash, Role role) {
        if (role == Role.CUSTOMER) {
            throw new IllegalArgumentException("Staff account cannot have role CUSTOMER");
        }
        if (phone == null) {
            throw new IllegalArgumentException("Staff account must have a phone (BR-TK-01)");
        }
        Account account = new Account();
        account.email = email;
        account.phone = phone;
        account.passwordHash = passwordHash;
        account.role = role;
        account.status = AccountStatus.ACTIVE;
        account.mustChangePassword = true;
        return account;
    }

    /**
     * UC08 — gửi lại mật khẩu ban đầu (BR-QT-02; docs/adr/0030): hash của mật khẩu tạm mới, mật khẩu cũ hết hiệu lực.
     * Như đặt lại mật khẩu (BR-TK-13): xóa bộ đếm sai và khóa tạm, {@code last_seen_at = NULL} vì mọi phiên bị hủy cùng
     * lúc (BR-TN-06). Vẫn bắt đổi mật khẩu lần đầu. Caller giữ khóa dòng, đã kiểm {@code ACTIVE} và
     * {@code must_change_password}, rồi hủy phiên sau lệnh này (thứ tự {@code accounts → sessions}).
     */
    public void reissueInitialPassword(String newPasswordHash) {
        if (role == Role.CUSTOMER || status != AccountStatus.ACTIVE || !mustChangePassword) {
            throw new IllegalStateException("Account " + id + " cannot receive a new initial password");
        }
        this.passwordHash = newPasswordHash;
        this.failedLoginCount = 0;
        this.firstFailedLoginAt = null;
        this.lockedUntil = null;
        this.lastSeenAt = null;
    }

    /**
     * UC08 — đổi chức vụ (BR-QT-05; docs/adr/0031): chỉ trong nhóm A05–A08, cả chức vụ cũ lẫn mới. Không đổi
     * {@code status}, khóa, mật khẩu, phiên: role được đọc lại từ DB ở mỗi request (docs/adr/0003). Caller giữ khóa dòng,
     * đã kiểm phân cấp và BR-QT-04, và chỉ gọi khi chức vụ mới khác chức vụ hiện tại.
     */
    public void changeStaffRole(Role newRole) {
        if (!BRANCH_STAFF_ROLES.contains(role) || !BRANCH_STAFF_ROLES.contains(newRole)) {
            throw new IllegalStateException("Account " + id + " cannot change role " + role + " -> " + newRole);
        }
        this.role = newRole;
    }

    /**
     * Tài khoản#5 — vô hiệu hóa nhân viên (BR-QT-07, 09; docs/adr/0033): {@code ACTIVE → DISABLED}. Đặt
     * {@code last_seen_at = NULL} vì mọi phiên bị hủy cùng lúc — chuyển offline ngay (BR-TN-06, docs/adr/0030 *Hệ quả*).
     * Dữ liệu khác giữ nguyên (BR-QT-09). Caller giữ khóa dòng, đã kiểm phân cấp, BR-QT-12, 04, 08 và
     * {@code validateTransition}, rồi hủy phiên sau lệnh này (thứ tự {@code accounts → sessions}).
     */
    public void disable() {
        if (role == Role.CUSTOMER || status != AccountStatus.ACTIVE || locked) {
            throw new IllegalStateException("Account " + id + " cannot be disabled");
        }
        this.status = AccountStatus.DISABLED;
        this.lastSeenAt = null;
    }

    /**
     * Tài khoản#6 — kích hoạt lại nhân viên (BR-QT-10; docs/adr/0033): {@code DISABLED → ACTIVE} với mật khẩu tạm mới
     * (BR-QT-02), bắt đổi mật khẩu như lần đầu (BR-TK-17). Như gửi lại mật khẩu ban đầu: xóa bộ đếm sai và khóa tạm,
     * {@code last_seen_at = NULL} vì mọi phiên bị hủy. Caller giữ khóa dòng, đã kiểm phân cấp, BR-QT-10, 03 và
     * {@code validateTransition}, rồi hủy phiên sau lệnh này.
     */
    public void reactivate(String newPasswordHash) {
        if (role == Role.CUSTOMER || status != AccountStatus.DISABLED || locked) {
            throw new IllegalStateException("Account " + id + " cannot be reactivated");
        }
        this.status = AccountStatus.ACTIVE;
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = true;
        this.failedLoginCount = 0;
        this.firstFailedLoginAt = null;
        this.lockedUntil = null;
        this.lastSeenAt = null;
    }

    /**
     * Tài khoản#7 — ADMIN khóa tài khoản (BR-QT-11, BR-TK-11; docs/adr/0035): {@code is_locked = true}, lưu lý do gần
     * nhất ({@code locked_reason}). {@code status} giữ nguyên để mở khóa trả về đúng trạng thái trước đó (BR-QT-12).
     * Đặt {@code last_seen_at = NULL} vì mọi phiên bị hủy cùng lúc — chuyển offline ngay (BR-TN-06). Áp dụng cho mọi role
     * và mọi {@code status}. Caller giữ khóa dòng, đã kiểm "không tự khóa" và chưa bị khóa, rồi hủy phiên sau lệnh này
     * (thứ tự {@code accounts → sessions}, docs/adr/0021). {@code reason} đã strip.
     */
    public void lock(String reason) {
        if (locked) {
            throw new IllegalStateException("Account " + id + " is already locked");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Account " + id + " needs a lock reason (BR-QT-11)");
        }
        this.locked = true;
        this.lockedReason = reason;
        this.lastSeenAt = null;
    }

    /**
     * Tài khoản#8 — ADMIN mở khóa (BR-QT-11, 12; docs/adr/0035): chỉ gỡ {@code is_locked}, nên {@code status} là giá trị
     * ngay trước khi khóa ({@code ACTIVE} hoặc {@code DISABLED}). Giữ {@code locked_reason} (erd: "lý do khóa gần
     * nhất"). Không mở phiên, không đổi mật khẩu. Caller giữ khóa dòng và đã kiểm đang bị khóa.
     */
    public void unlock() {
        if (!locked) {
            throw new IllegalStateException("Account " + id + " is not locked");
        }
        this.locked = false;
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

    /**
     * BR-TK-16 — người có thẩm quyền sửa hộ email sau khi email mới đã xác thực OTP (docs/adr/0034). Chỉ đổi
     * {@code email}: trạng thái, khóa, mật khẩu, phiên giữ nguyên (đổi email không phải chuyển trạng thái của 03 §1).
     * {@code newEmail} đã chuẩn hóa chữ thường; caller giữ khóa dòng, đã kiểm phân cấp, mã OTP và BR-TK-01
     * ({@code uq_accounts_email} là chốt cuối). Tài khoản {@code PENDING} chưa xác thực email nên không đổi được.
     */
    public void changeEmail(String newEmail) {
        if (newEmail == null || newEmail.isBlank()) {
            throw new IllegalArgumentException("Account " + id + " needs a non-blank email (BR-TK-01)");
        }
        if (status == AccountStatus.PENDING) {
            throw new IllegalStateException("Pending account " + id + " cannot change email");
        }
        this.email = newEmail;
    }
}
