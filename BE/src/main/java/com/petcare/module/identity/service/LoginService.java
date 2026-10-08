package com.petcare.module.identity.service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.LoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.repository.AccountCredential;
import com.petcare.platform.audit.AuditRecorder;

/**
 * UC03 — đăng nhập ({@code POST /api/auth/login}, docs/adr/0019). Cố ý <b>không</b> {@code @Transactional}
 * (mục 4): so BCrypt (~80 ms CPU) khi không giữ connection, để request đăng nhập dồn dập không làm cạn pool. Phần đọc
 * lại trạng thái dưới khóa, ghi bộ đếm, mở phiên, audit nằm trong {@link LoginAttemptService} (transaction riêng, đi qua
 * proxy vì là bean khác).
 * <ol>
 *   <li>Đọc {@code (id, password_hash)} — transaction readOnly ngắn.</li>
 *   <li>So BCrypt; email lạ hoặc mật khẩu &gt; 72 byte so với hash giả để thời gian phản hồi xấp xỉ (BR-TK-10).</li>
 *   <li>{@link LoginAttemptService#login} / {@link LoginAttemptService#rejectUnknownEmail}.</li>
 *   <li>{@link LoginRejectedException} (400): transaction đã rollback và nhả khóa, ghi audit
 *       {@code recordIndependently} rồi ném lại (mục 6).</li>
 * </ol>
 */
@Service
public class LoginService {

    private final LoginAttemptService attempts;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder audit;
    /** Hash giả cùng cost BCrypt (docs/adr/0009), tính một lần lúc khởi động (docs/adr/0019 mục 5). */
    private final String dummyHash;

    public LoginService(LoginAttemptService attempts, PasswordEncoder passwordEncoder, AuditRecorder audit) {
        this.attempts = attempts;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String password = request.password();

        Optional<AccountCredential> credential = attempts.findCredential(email);
        boolean matched = matches(password, credential.map(AccountCredential::passwordHash).orElse(null));
        if (credential.isEmpty()) {
            attempts.rejectUnknownEmail(email);   // luôn ném InvalidCredentialsException sau khi ghi audit
            throw new IllegalStateException("rejectUnknownEmail must throw");
        }
        try {
            return attempts.login(credential.get(), email, password, matched, ipAddress, userAgent);
        } catch (LoginRejectedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    /** Luôn chạy đúng một lần BCrypt, kể cả khi không có hash thật để so. */
    private boolean matches(String password, String passwordHash) {
        if (passwordHash == null || !LoginAttemptService.fitsBcrypt(password)) {
            passwordEncoder.matches(password, dummyHash);
            return false;
        }
        return passwordEncoder.matches(password, passwordHash);
    }
}
