package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.audit.AuditRecorder;

/**
 * UC08 — facade của tạo nhân viên và gửi lại mật khẩu ban đầu (identity-v1 #16, #22; docs/adr/0029 mục 2, docs/adr/0030).
 * Cố ý <b>không</b> {@code @Transactional} (khuôn {@code LoginService}, docs/adr/0019 mục 4, 6; convention 07 §7.1):
 * <ol>
 *   <li>Sinh mật khẩu tạm + BCrypt khi không giữ connection hay khóa dòng ({@link TemporaryPasswordGenerator}).</li>
 *   <li>{@link StaffAccountService}: một transaction trọn use case (bean khác, đi qua proxy).</li>
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

    public StaffAdminService(StaffAccountService staffAccounts, TemporaryPasswordGenerator temporaryPasswords,
            AuditRecorder audit) {
        this.staffAccounts = staffAccounts;
        this.temporaryPasswords = temporaryPasswords;
        this.audit = audit;
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
}
