package com.petcare.module.identity.api;

import java.util.Collection;
import java.util.List;

/**
 * Owner: identity (TK) · BE-1. Email của tài khoản để module khác gửi thông báo cho khách (BR-TB-02): hồ sơ online có
 * {@code customers.email = null}, email thật nằm ở {@code accounts} (thêm 2026-10-18, T25 phần 1, docs/adr/0047).
 * Chỉ đọc, tham gia transaction của bên gọi, không khóa, không kiểm role (API nội bộ, bên gọi đã phân quyền).
 */
public interface AccountContactApi {

    /**
     * {@code pending}: tài khoản chưa xác thực email (BR-TK-08). Bên gọi coi như không có tài khoản: không truyền
     * {@code recipientAccountId} (FK chặn ST02 xóa tài khoản) và không gửi tới email chưa xác thực.
     */
    record AccountContact(Long accountId, String email, boolean pending) {}

    /**
     * Một câu SQL bất kể số id. Id không có thì bỏ qua, mỗi id tối đa một lần (trùng trong đầu vào bị khử), thứ tự tùy
     * ý, mọi trạng thái kể cả bị khóa; tập rỗng → rỗng, không chạm DB. Bên gọi truyền tối đa 100 id.
     */
    List<AccountContact> findContacts(Collection<Long> accountIds);
}
