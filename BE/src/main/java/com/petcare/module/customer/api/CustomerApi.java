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

    /**
     * Tài khoản#3 (ST02): xóa hồ sơ online tạo kèm tài khoản PENDING quá hạn (BR-TK-08). Identity đã khóa dòng
     * {@code accounts} và xóa {@code otp_tokens}; xóa {@code accounts} sau lời gọi này. Phải {@code MANDATORY} và
     * không có tác dụng nằm ngoài transaction ({@code REQUIRES_NEW}, {@code @Async}, {@code AFTER_COMMIT},
     * {@code recordIndependently}): transaction lỗi thì ST02 làm lại ở lượt sau, tác dụng ngoài sẽ bị lặp
     * (docs/adr/0013).
     */
    void deleteOnlineProfileOfUnverifiedAccount(Long accountId);

    /**
     * BR-TK-19 (a), UC07: gắn tài khoản vào hồ sơ tại quầy, sau khi identity đã khóa dòng {@code accounts} và tiêu mã OTP
     * trong cùng transaction (thứ tự khóa {@code accounts → customers}, docs/adr/0025 mục 8, docs/adr/0027).
     * <p>
     * Nghĩa vụ của implementation (kiểm khi trả nợ D001):
     * <ul>
     *   <li>{@code MANDATORY}; không {@code REQUIRES_NEW}, {@code @Async}, {@code AFTER_COMMIT}: identity rollback thì
     *       không còn dấu vết liên kết nào.</li>
     *   <li>Khóa dòng hồ sơ tại quầy {@code counterCustomerId} (C) và hồ sơ của tài khoản (O), rồi kiểm lại dưới khóa;
     *       sai thì ném {@code BusinessRuleViolationException("BR-TK-19", …)} — chỉ exception này, không
     *       {@code OtpRejectedException} hay lớp con của nó (sẽ lọt {@code noRollbackFor} của identity):
     *       O phải là {@code ONLINE} (tài khoản chưa liên kết hồ sơ tại quầy nào) và chưa phát sinh dữ liệu (thú cưng,
     *       lịch hẹn, đặt chỗ, Order, feedback); C phải là {@code COUNTER}, chưa có {@code account_id}, và
     *       {@code email = expectedCounterEmail} (email đã nhận mã).</li>
     *   <li>Xóa {@code addresses} của O, xóa O <b>và {@code flush()}</b> (Hibernate flush UPDATE trước DELETE;
     *       {@code uq_customers_account_id} không deferrable), rồi mới gán {@code C.account_id = accountId},
     *       {@code C.email = accountEmail}; SĐT của C giữ nguyên. Sổ địa chỉ của hồ sơ online mất theo O (docs/adr/0027).</li>
     *   <li>Không ghi audit: identity ghi {@code CUSTOMER_PROFILE_LINKED} trong cùng transaction (convention 08 §8.3).</li>
     * </ul>
     */
    void linkAccountToCounterProfile(Long accountId, Long counterCustomerId, String expectedCounterEmail,
            String accountEmail);

    /**
     * BR-TK-19 (b) "Không phải tôi": gỡ cờ chờ quyết định liên kết của hồ sơ online. {@code MANDATORY}; identity đã khóa
     * dòng {@code accounts} và kiểm cờ đang bật (docs/adr/0027).
     */
    void declineLink(Long accountId);
}
