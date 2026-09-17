package com.petcare.module.pet.exception;

import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * RULE-04-09 — thao tác ngoài phạm vi được ủy quyền.
 *
 * Lý do tạo exception riêng (docs/convention/backend/04-exception-handling.md §4.2,
 * TIÊU CHÍ 2): cần errorCode riêng `UNAUTHORIZED_DELEGATED_ACTION` mà contract
 * docs/api/customer-pet-v1.md chốt CONFIRMED cho RULE-04-09, khác với
 * `ACCESS_DENIED_SCOPE_MISMATCH` mặc định của class cha (cùng HTTP 403).
 */
public class UnauthorizedDelegatedActionException extends AccessDeniedScopeException {

    public UnauthorizedDelegatedActionException(String requiredScope, String actualScope) {
        super(requiredScope, actualScope);
    }
}
