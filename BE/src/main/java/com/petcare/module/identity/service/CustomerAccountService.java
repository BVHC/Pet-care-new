package com.petcare.module.identity.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.CustomerEmailChangeRequest;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * UC22 — facade của lễ tân xử lý tài khoản khách tại quầy: sửa hộ email (identity-v1 #25, #26; BR-TK-16). Cố ý <b>không</b>
 * {@code @Transactional} (khuôn {@link StaffAdminService}, docs/adr/0019 mục 4, 6, docs/adr/0023; convention 07 §7.1):
 * sinh và băm mã OTP, so mã bằng BCrypt đều chạy ở đây, khi không giữ connection hay khóa dòng; phần đọc / ghi nằm ở
 * {@link CustomerEmailChangeService} (bean khác, đi qua proxy). Không được gọi từ trong một transaction khác: khi đó lỗi
 * sẽ làm transaction bên ngoài rollback-only.
 */
@Service
public class CustomerAccountService {

    private final CustomerEmailChangeService emailChanges;
    private final OtpService otps;
    private final PasswordEncoder passwordEncoder;

    public CustomerAccountService(CustomerEmailChangeService emailChanges, OtpService otps,
            PasswordEncoder passwordEncoder) {
        this.emailChanges = emailChanges;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
    }

    /** identity-v1 #25. Sinh và băm mã trước, ngoài transaction. */
    public OtpSentResponse startEmailChange(Long customerId, CustomerEmailChangeRequest request) {
        PreparedOtp prepared = otps.prepare();
        return emailChanges.issueEmailChangeOtp(customerId, request.newEmail(), prepared);
    }

    /**
     * identity-v1 #26. Đọc mã còn mở (readOnly; 404, 400 trước khi tra mã) → so BCrypt ngoài transaction → ghi dưới khóa
     * ({@link CustomerEmailChangeService#applyEmailChange}). Không còn mã → BR-TK-05, ném sau khi transaction đọc đã kết
     * thúc.
     */
    public void confirmEmailChange(Long customerId, OtpCodeRequest request) {
        EmailChangeCode found = emailChanges.findEmailChangeCode(customerId)
                .orElseThrow(() -> new BusinessRuleViolationException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE));
        boolean matched = passwordEncoder.matches(request.code(), found.codeHash());
        emailChanges.applyEmailChange(customerId, new EmailChangeAttempt(found.otpId(), found.newEmail(), matched));
    }
}
