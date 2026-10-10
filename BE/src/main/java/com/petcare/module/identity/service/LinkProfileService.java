package com.petcare.module.identity.service;

import java.util.List;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkConfirmRequest;
import com.petcare.module.identity.dto.LinkOtpRequest;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkAttempt;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkCode;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * UC07 — liên kết tài khoản khách với hồ sơ khách có sẵn tại quầy (identity-v1 #11–14; BR-TK-04…07, 19;
 * docs/adr/0025, docs/adr/0027). Cố ý <b>không</b> {@code @Transactional}, như {@link PasswordResetService}: sinh / so
 * BCrypt ở đây khi không giữ connection hay khóa dòng; phần đọc / ghi nằm ở {@link LinkProfileAttemptService} (bean
 * khác, đi qua proxy). Luôn thao tác trên tài khoản của chủ token.
 */
@Service
public class LinkProfileService {

    private final LinkProfileAttemptService attempts;
    private final OtpService otps;
    private final PasswordEncoder passwordEncoder;
    private final BranchScope branchScope;

    public LinkProfileService(LinkProfileAttemptService attempts, OtpService otps, PasswordEncoder passwordEncoder,
            BranchScope branchScope) {
        this.attempts = attempts;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
        this.branchScope = branchScope;
    }

    /** identity-v1 #11. */
    public List<LinkCandidateResponse> listCandidates(String phone) {
        return attempts.listCandidates(currentAccountId(), phone);
    }

    /** identity-v1 #12. Sinh và băm mã trước, ngoài transaction (docs/adr/0025 mục 1). */
    public OtpSentResponse sendLinkOtp(LinkOtpRequest request) {
        OtpService.PreparedOtp prepared = otps.prepare();
        return attempts.issueLinkOtp(currentAccountId(), request.customerId(), request.phone(), prepared);
    }

    /**
     * identity-v1 #13. Đọc mã còn mở (readOnly) → so BCrypt ngoài transaction → ghi dưới khóa
     * ({@link LinkProfileAttemptService#applyLink}). Không có mã → BR-TK-05, ném sau khi transaction đọc đã kết thúc.
     */
    public LinkResult confirmLink(LinkConfirmRequest request) {
        Long accountId = currentAccountId();
        Optional<LinkCode> code = attempts.findLinkCode(accountId, request.customerId());
        if (code.isEmpty()) {
            throw new BusinessRuleViolationException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE);
        }
        LinkCode found = code.get();
        boolean matched = passwordEncoder.matches(request.code(), found.codeHash());
        return attempts.applyLink(accountId,
                new LinkAttempt(found.customerId(), found.counterEmail(), found.otpId(), matched));
    }

    /** identity-v1 #14. */
    public void declineLink() {
        attempts.decline(currentAccountId());
    }

    private Long currentAccountId() {
        return branchScope.current().accountId();
    }
}
