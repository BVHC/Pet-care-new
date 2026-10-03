package com.petcare.module.customer.api;

/**
 * Owner: customer (KH) · BE-2. Caller: identity (TK) · BE-1.
 * Mọi method ghi được gọi bên trong transaction của use case phía caller.
 */
public interface CustomerApi {

    /** Tài khoản#1: tạo hồ sơ online gắn tài khoản (BR-KH-01). {@code phone} có thể null. Trả về customerId. */
    Long createOnlineProfile(Long accountId, String fullName, String phone);

    /**
     * Tài khoản#2: nếu SĐT của hồ sơ online trùng ít nhất 1 hồ sơ tại quầy chưa liên kết thì đặt
     * {@code link_decision_pending = true} (BR-TK-19). Trả về giá trị cờ sau khi xử lý.
     */
    boolean flagLinkDecisionIfPhoneMatches(Long accountId);

    /** Tài khoản#3 (ST02): xóa hồ sơ online tạo kèm tài khoản PENDING quá hạn (BR-TK-08). */
    void deleteOnlineProfileOfUnverifiedAccount(Long accountId);

    /**
     * BR-TK-19 (a), UC07, UC22: gắn tài khoản vào hồ sơ tại quầy, sau khi identity đã xác thực OTP.
     * Hồ sơ online phải chưa phát sinh dữ liệu (thú cưng, lịch hẹn, đặt chỗ, Order, feedback) và bị xóa;
     * email hồ sơ tại quầy cập nhật theo email tài khoản, SĐT giữ nguyên. Ghi audit.
     */
    void linkAccountToCounterProfile(Long accountId, Long counterCustomerId, String accountEmail, Long actorId);

    /** BR-TK-19 (b) "Không phải tôi": gỡ cờ chờ quyết định liên kết. */
    void declineLink(Long accountId);
}
