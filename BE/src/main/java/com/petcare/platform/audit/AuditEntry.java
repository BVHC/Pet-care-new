package com.petcare.platform.audit;

/**
 * Một sự kiện cần ghi audit (BR-QT-15). Dựng bằng {@link #of(String)} rồi các hàm {@code with}:
 *
 * <pre>{@code
 * AuditEntry.of("PRICE_CHANGED").entity("products", id).before(old).after(updated).reason(reason)
 * }</pre>
 *
 * {@code before}/{@code after} là snapshot do service tự chọn trường (record hoặc Map), không bao giờ là entity JPA;
 * xem quy tắc ở {@link AuditRecorder}. Actor mặc định lấy từ {@code SecurityContext}; {@link #actor(Long, String)}
 * ghi đè khi chưa có phiên đăng nhập (ví dụ {@code LOGIN_FAILED} ghi email người dùng đã nhập).
 */
public record AuditEntry(
        String action,
        String entityType,
        Long entityId,
        Object before,
        Object after,
        String reason,
        Long actorAccountId,
        String actorEmail,
        boolean actorOverridden) {

    public static AuditEntry of(String action) {
        return new AuditEntry(action, null, null, null, null, null, null, null, false);
    }

    /** {@code entityType} là tên bảng ({@code accounts}, {@code orders}...); {@code entityId} được để trống. */
    public AuditEntry entity(String type, Long id) {
        return new AuditEntry(action, type, id, before, after, reason, actorAccountId, actorEmail, actorOverridden);
    }

    public AuditEntry before(Object snapshot) {
        return new AuditEntry(action, entityType, entityId, snapshot, after, reason, actorAccountId, actorEmail,
                actorOverridden);
    }

    public AuditEntry after(Object snapshot) {
        return new AuditEntry(action, entityType, entityId, before, snapshot, reason, actorAccountId, actorEmail,
                actorOverridden);
    }

    public AuditEntry reason(String text) {
        return new AuditEntry(action, entityType, entityId, before, after, text, actorAccountId, actorEmail,
                actorOverridden);
    }

    /** Ghi đè người thực hiện. {@code (null, null)} = hệ thống; {@code (null, email)} = email chưa khớp tài khoản. */
    public AuditEntry actor(Long accountId, String email) {
        return new AuditEntry(action, entityType, entityId, before, after, reason, accountId, email, true);
    }
}
