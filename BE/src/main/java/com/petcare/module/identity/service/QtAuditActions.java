package com.petcare.module.identity.service;

/**
 * Mã action audit của nhóm quản trị (QT) trong module identity (convention 03: {@code <ĐỐI_TƯỢNG>_<QUÁ_KHỨ>}; danh mục
 * ở convention 08 §8.3). Mã của từng thao tác UC08, UC09 thêm cùng phần T14 cài thao tác đó.
 */
public final class QtAuditActions {

    /**
     * Request vượt phân cấp bị từ chối 403 (BR-QT-01, 07, BR-TK-16; docs/adr/0029 mục 2). Ghi bằng
     * {@code recordIndependently} sau khi transaction nghiệp vụ đã rollback. {@code entity = accounts/<người bị tác
     * động>} (không có với {@code CREATE}); {@code after} = {@code action, targetRole, targetBranchId, requestedRole,
     * requestedBranchId}.
     */
    public static final String STAFF_ACTION_DENIED = "STAFF_ACTION_DENIED";

    /**
     * Tạo tài khoản nhân viên (Tài khoản#4, BR-QT-15; docs/adr/0030). {@code entity = accounts/<id>}; {@code after} =
     * {@code email, phone, fullName, role, branchId, status}. Không có khóa {@code mustChangePassword}: tên chứa từ
     * {@code password} nên {@code AuditRecorder} từ chối; cờ luôn {@code true} khi tạo.
     */
    public static final String STAFF_CREATED = "STAFF_CREATED";

    /**
     * Gửi lại mật khẩu ban đầu (BR-QT-02, 15; docs/adr/0030). {@code entity = accounts/<id>}, không snapshot: thứ duy
     * nhất thay đổi có giá trị là mật khẩu (bí mật).
     */
    public static final String STAFF_INITIAL_PASSWORD_RESENT = "STAFF_INITIAL_PASSWORD_RESENT";

    /**
     * Đổi chức vụ (BR-QT-05, 15; docs/adr/0031). {@code entity = accounts/<id>}; {@code before}, {@code after} =
     * {@code role, specialty, bio} (VET đổi sang chức vụ khác thì {@code specialty}, {@code bio} thành {@code null}).
     */
    public static final String STAFF_ROLE_CHANGED = "STAFF_ROLE_CHANGED";

    /**
     * Điều chuyển nhân viên (BR-QT-06, 15; docs/adr/0031). {@code entity = accounts/<id>}; {@code before},
     * {@code after} = {@code branchId}.
     */
    public static final String STAFF_TRANSFERRED = "STAFF_TRANSFERRED";

    /**
     * Vô hiệu hóa nhân viên (Tài khoản#5, BR-QT-07, 09, 15; docs/adr/0033). {@code entity = accounts/<id>};
     * {@code before}, {@code after} = {@code status}.
     */
    public static final String STAFF_DISABLED = "STAFF_DISABLED";

    /**
     * Kích hoạt lại nhân viên (Tài khoản#6, BR-QT-10, 15; docs/adr/0033). {@code entity = accounts/<id>};
     * {@code before}, {@code after} = {@code status, branchId}. Không có mật khẩu tạm (bí mật, chỉ ở outbox).
     */
    public static final String STAFF_REACTIVATED = "STAFF_REACTIVATED";

    /**
     * Sửa hộ email nhân viên sau khi email mới xác thực OTP (BR-TK-16, BR-QT-15; docs/adr/0034). Chỉ ghi khi xác nhận
     * thành công (bước gửi mã không đổi dữ liệu). {@code entity = accounts/<id>}; {@code before} = {@code email};
     * {@code after} = {@code email, verificationMethod} ({@code EMAIL_CODE}). Không có mã OTP.
     */
    public static final String STAFF_EMAIL_CHANGED = "STAFF_EMAIL_CHANGED";

    /**
     * ADMIN khóa tài khoản bất kỳ, trừ chính mình (Tài khoản#7, BR-QT-11, 15; docs/adr/0035). {@code entity =
     * accounts/<id>}; {@code before}, {@code after} = {@code isLocked, status}; {@code reason} = lý do ADMIN nhập.
     */
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    /**
     * ADMIN mở khóa (Tài khoản#8, BR-QT-11, 12, 15; docs/adr/0035). {@code entity = accounts/<id>}; {@code before},
     * {@code after} = {@code isLocked, status} ({@code status} không đổi); {@code reason} = lý do ADMIN nhập.
     */
    public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";

    private QtAuditActions() {
    }
}
