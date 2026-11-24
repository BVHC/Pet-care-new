package com.petcare.module.identity.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.GoogleLoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.google.GoogleIdentity;
import com.petcare.module.identity.google.GoogleIdentityVerifier;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Đăng nhập / đăng ký bằng Google ({@code POST /api/auth/google}, identity-v1 #40, docs/adr/0152). Cố ý <b>không</b>
 * {@code @Transactional}, cùng mẫu {@link LoginService}:
 * <ol>
 *   <li>Kiểm ID token qua JWKS của Google (gọi mạng) khi chưa mở transaction; email đã chuẩn hóa ở verifier.</li>
 *   <li>{@link LoginAttemptService#loginWithGoogle} (transaction riêng, đi qua proxy vì là bean khác).</li>
 *   <li>{@link LoginRejectedException} (400): transaction đã rollback và nhả khóa, ghi audit
 *       {@code recordIndependently} rồi ném lại.</li>
 * </ol>
 */
@Service
public class GoogleLoginService {

    private final GoogleIdentityVerifier verifier;
    private final LoginAttemptService attempts;
    private final AuditRecorder audit;
    /**
     * Hash BCrypt không ai biết mật khẩu, lưu cho tài khoản tạo bằng Google: tính một lần lúc khởi động để không chạy
     * BCrypt trong transaction; không giữ chuỗi gốc (cùng ý tưởng {@code dummyHash} của {@link LoginService}).
     */
    private final String unusableHash;

    public GoogleLoginService(GoogleIdentityVerifier verifier, LoginAttemptService attempts,
            PasswordEncoder passwordEncoder, AuditRecorder audit) {
        this.verifier = verifier;
        this.attempts = attempts;
        this.audit = audit;
        this.unusableHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(GoogleLoginRequest request, String ipAddress, String userAgent) {
        GoogleIdentity identity = verifier.verify(request.credential());
        try {
            return attempts.loginWithGoogle(identity, request, unusableHash, ipAddress, userAgent);
        } catch (LoginRejectedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }
}
