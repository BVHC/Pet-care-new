package com.petcare.module.identity.service;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.EmailChangeRequest;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.ReactivateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * UC08 — facade của tạo nhân viên, gửi lại mật khẩu ban đầu, đổi chức vụ, điều chuyển, vô hiệu hóa, kích hoạt lại, sửa
 * email (identity-v1 #16, #22, #18, #19, #20, #21, #23, #24; docs/adr/0029 mục 2, docs/adr/0030, 0031, 0033, 0034).
 * Cố ý <b>không</b> {@code @Transactional} (khuôn {@code LoginService}, docs/adr/0019 mục 4, 6; convention 07 §7.1):
 * <ol>
 *   <li>Tạo / gửi lại / kích hoạt lại: sinh mật khẩu tạm + BCrypt khi không giữ connection hay khóa dòng
 *       ({@link TemporaryPasswordGenerator}). Sửa email: sinh / so mã OTP bằng BCrypt cũng ở đây.</li>
 *   <li>{@link StaffAccountService}, {@link StaffEmailChangeService}: một transaction trọn use case (bean khác, đi qua
 *       proxy).</li>
 *   <li>{@link StaffHierarchyDeniedException} (403 vượt phân cấp): transaction đã rollback và trả connection; ghi audit
 *       {@code STAFF_ACTION_DENIED} bằng {@code recordIndependently} rồi ném lại đúng lỗi đó. Lỗi khác không audit.</li>
 * </ol>
 * Không được gọi từ trong một transaction khác: khi đó lỗi sẽ làm transaction bên ngoài rollback-only.
 */
@Service
public class StaffAdminService {

    private final StaffAccountService staffAccounts;
    private final TemporaryPasswordGenerator temporaryPasswords;
    private final AuditRecorder audit;
    private final StaffEmailChangeService emailChanges;
    private final OtpService otps;
    private final PasswordEncoder passwordEncoder;

    public StaffAdminService(StaffAccountService staffAccounts, TemporaryPasswordGenerator temporaryPasswords,
            AuditRecorder audit, StaffEmailChangeService emailChanges, OtpService otps,
            PasswordEncoder passwordEncoder) {
        this.staffAccounts = staffAccounts;
        this.temporaryPasswords = temporaryPasswords;
        this.audit = audit;
        this.emailChanges = emailChanges;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
    }

    public StaffResponse createStaff(CreateStaffRequest request) {
        TemporaryPassword password = temporaryPasswords.prepare();
        try {
            return staffAccounts.createStaff(request, password);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    public void resendInitialPassword(Long accountId) {
        TemporaryPassword password = temporaryPasswords.prepare();
        try {
            staffAccounts.resendInitialPassword(accountId, password);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    public StaffResponse changeStaffRole(Long accountId, ChangeRoleRequest request) {
        try {
            return staffAccounts.changeStaffRole(accountId, request);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    public StaffResponse transferStaff(Long accountId, TransferStaffRequest request) {
        try {
            return staffAccounts.transferStaff(accountId, request);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    public StaffResponse disableStaff(Long accountId) {
        try {
            return staffAccounts.disableStaff(accountId);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    /** {@code request} có thể {@code null} (body không bắt buộc). */
    public StaffResponse reactivateStaff(Long accountId, ReactivateStaffRequest request) {
        TemporaryPassword password = temporaryPasswords.prepare();
        try {
            return staffAccounts.reactivateStaff(accountId, request, password);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    /** identity-v1 #23 (docs/adr/0034). Sinh và băm mã trước, ngoài transaction. */
    public OtpSentResponse startEmailChange(Long accountId, EmailChangeRequest request) {
        PreparedOtp prepared = otps.prepare();
        try {
            return emailChanges.issueEmailChangeOtp(accountId, request.newEmail(), prepared);
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }

    /**
     * identity-v1 #24 (docs/adr/0034). Đọc mã còn mở (readOnly; 404, 403 trước khi tra mã) → so BCrypt ngoài transaction
     * → ghi dưới khóa ({@link StaffEmailChangeService#applyEmailChange}). Không còn mã → BR-TK-05, ném sau khi transaction
     * đọc đã kết thúc. 403 ở bước nào cũng ghi audit sau rollback.
     */
    public StaffResponse confirmEmailChange(Long accountId, OtpCodeRequest request) {
        try {
            Optional<EmailChangeCode> code = emailChanges.findEmailChangeCode(accountId);
            if (code.isEmpty()) {
                throw new BusinessRuleViolationException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE);
            }
            EmailChangeCode found = code.get();
            boolean matched = passwordEncoder.matches(request.code(), found.codeHash());
            return emailChanges.applyEmailChange(accountId,
                    new EmailChangeAttempt(found.otpId(), found.newEmail(), matched));
        } catch (StaffHierarchyDeniedException ex) {
            audit.recordIndependently(ex.auditEntry());
            throw ex;
        }
    }
}
