package com.petcare.module.identity.exception;

import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * Request vượt phân cấp quản trị nhân viên (BR-QT-01, 07, BR-TK-16; docs/adr/0029 mục 2). Client nhận như mọi
 * {@link AccessDeniedScopeException}: 403 {@code ACCESS_DENIED_SCOPE_MISMATCH}, message chung (00-method L65).
 * <p>
 * Lớp riêng theo tiêu chí 3 của convention 04 §4.2 (logic rollback riêng, cùng khuôn {@code LoginRejectedException},
 * docs/adr/0019 mục 6): service ném lỗi này dưới khóa dòng, <b>trước mọi lệnh ghi</b>; transaction rollback và trả
 * connection; facade không {@code @Transactional} bắt lỗi rồi ghi {@link #auditEntry()} bằng
 * {@code AuditRecorder.recordIndependently} và ném lại. Ghi ngay trong transaction đang giữ khóa thì cần connection
 * thứ hai. Không bắt lỗi này bên trong một transaction: nó đã đánh dấu transaction rollback-only khi đi qua proxy.
 */
public class StaffHierarchyDeniedException extends AccessDeniedScopeException {

    private final transient AuditEntry auditEntry;

    public StaffHierarchyDeniedException(String requiredScope, String actualScope, AuditEntry auditEntry) {
        super(requiredScope, actualScope);
        this.auditEntry = auditEntry;
    }

    public AuditEntry auditEntry() {
        return auditEntry;
    }
}
