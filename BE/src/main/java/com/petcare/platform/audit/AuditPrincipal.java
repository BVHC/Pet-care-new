package com.petcare.platform.audit;

/**
 * Principal trong {@code SecurityContext} mà {@link AuditRecorder} đọc ra người thực hiện. Principal của module TK
 * implement interface này, nhờ vậy {@code platform/} không phụ thuộc {@code module/} (docs/adr/0001-audit-recording.md).
 */
public interface AuditPrincipal {

    /** {@code accounts.id} của người đang đăng nhập. */
    Long accountId();

    /** Email đăng nhập; bắt buộc vì {@code audit_logs.actor_account_id} không có FK (erd §13 mục 9). */
    String email();
}
