package com.petcare.module.sales.exception;

import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * Không phải chủ dòng, hoặc dòng đã khóa (BR-BH-02). Hợp đồng sales-v1 trả 403 kèm mã {@code BR-BH-02} để FE hiện
 * đúng lý do, nên khác {@link AccessDeniedScopeException} ở chỗ message trả client có mã rule (lý do tạo lớp con:
 * khác nội dung message, status giữ nguyên). Chi tiết phạm vi chỉ ghi log.
 */
public class OrderLineAccessDeniedException extends AccessDeniedScopeException {

    public OrderLineAccessDeniedException(String requiredScope, String actualScope) {
        super(requiredScope, actualScope);
    }

    @Override
    public String clientMessage() {
        return "Không có quyền thực hiện thao tác này trên dòng đơn hàng (BR-BH-02)";
    }
}
