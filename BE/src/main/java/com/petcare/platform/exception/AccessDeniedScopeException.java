package com.petcare.platform.exception;

/**
 * Người dùng truy cập dữ liệu ngoài phạm vi của mình (ví dụ nhân viên chi nhánh khác).
 * Chi tiết phạm vi chỉ ghi vào log; client nhận message chung để không lộ cấu trúc phân quyền.
 */
public class AccessDeniedScopeException extends PlatformException {

    private final String requiredScope;
    private final String actualScope;

    public AccessDeniedScopeException(String requiredScope, String actualScope) {
        super(ErrorCode.ACCESS_DENIED_SCOPE_MISMATCH,
                "Phạm vi yêu cầu " + requiredScope + ", phạm vi hiện tại " + actualScope);
        this.requiredScope = requiredScope;
        this.actualScope = actualScope;
    }

    @Override
    public String clientMessage() {
        return "Không có quyền truy cập dữ liệu này";
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public String getActualScope() {
        return actualScope;
    }
}
