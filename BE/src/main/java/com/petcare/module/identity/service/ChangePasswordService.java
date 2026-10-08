package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.ChangePasswordRequest;
import com.petcare.module.identity.repository.AccountPasswordState;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC05 — đổi mật khẩu ({@code POST /api/me/password}, BR-TK-03, 09, 14, 17; docs/adr/0022). Cố ý <b>không</b>
 * {@code @Transactional}, như {@link LoginService} (docs/adr/0019 mục 4): hai lần BCrypt (so mật khẩu hiện tại, mã hóa
 * mật khẩu mới) chạy khi không giữ connection và không giữ khóa dòng. Phần ghi nằm trong
 * {@link ChangePasswordAttemptService#apply} (bean khác, đi qua proxy).
 * <ol>
 *   <li>Đọc {@code (password_hash, locked_until)} — transaction readOnly ngắn.</li>
 *   <li>Đang khóa tạm → 400 BR-TK-09, không BCrypt, không đếm: người cầm phiên không được đoán mật khẩu hiện tại
 *       trong lúc khóa; chủ tài khoản dùng Quên mật khẩu (BR-TK-12, 13).</li>
 *   <li>So BCrypt; &gt; 72 byte là sai (docs/adr/0019 mục 7).</li>
 *   <li>Đúng → {@link NewPasswordHasher}: BR-TK-03 rồi mã hóa. Thứ tự BR-TK-14 trước BR-TK-03 để mọi lần nhập sai
 *       mật khẩu hiện tại đều được đếm (BR-TK-14).</li>
 *   <li>{@link ChangePasswordAttemptService#apply}: khóa dòng, kiểm lại, ghi.</li>
 * </ol>
 */
@Service
public class ChangePasswordService {

    private final ChangePasswordAttemptService attempts;
    private final NewPasswordHasher newPasswordHasher;
    private final PasswordEncoder passwordEncoder;
    private final BranchScope branchScope;
    private final Clock clock;

    public ChangePasswordService(ChangePasswordAttemptService attempts, NewPasswordHasher newPasswordHasher,
            PasswordEncoder passwordEncoder, BranchScope branchScope, Clock clock) {
        this.attempts = attempts;
        this.newPasswordHasher = newPasswordHasher;
        this.passwordEncoder = passwordEncoder;
        this.branchScope = branchScope;
        this.clock = clock;
    }

    public void changePassword(ChangePasswordRequest request) {
        SecurityPrincipal principal = branchScope.current();
        String currentPassword = request.currentPassword();
        String newPassword = request.newPassword();

        AccountPasswordState state = attempts.findPasswordState(principal.accountId());
        if (state.lockedUntil() != null && Instant.now(clock).isBefore(state.lockedUntil())) {
            throw temporarilyLocked(state.lockedUntil());
        }

        boolean matched = LoginAttemptService.fitsBcrypt(currentPassword)
                && passwordEncoder.matches(currentPassword, state.passwordHash());
        String newPasswordHash = matched ? newPasswordHasher.hash(currentPassword, newPassword) : null;

        attempts.apply(principal.accountId(), principal.sessionId(), state.passwordHash(), currentPassword,
                newPassword, matched, newPasswordHash);
    }

    /** 400 BR-TK-09 kèm giờ thử lại làm tròn lên phút, cùng message với đăng nhập. */
    static BusinessRuleViolationException temporarilyLocked(Instant lockedUntil) {
        return new BusinessRuleViolationException("BR-TK-09",
                LoginAttemptService.MSG_TEMPORARILY_LOCKED.formatted(
                        LoginAttemptService.formatUnlockTime(lockedUntil)));
    }
}
