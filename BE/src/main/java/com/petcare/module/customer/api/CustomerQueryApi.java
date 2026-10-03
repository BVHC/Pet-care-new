package com.petcare.module.customer.api;

import java.util.List;
import java.util.Optional;

/** Owner: customer (KH) · BE-2. */
public interface CustomerQueryApi {

    /** Thông tin liên lạc để chọn kênh nhắc (BR-TB-02) và gửi thông báo. */
    record CustomerContact(Long customerId, String fullName, String phone, String email,
                           Long accountId, boolean linkDecisionPending) {}

    Optional<CustomerContact> findContact(Long customerId);

    /** Hồ sơ khách gắn với tài khoản (BR-KH-01: tài khoản khách luôn có hồ sơ). */
    Optional<Long> findCustomerIdByAccountId(Long accountId);

    /** {@code maskedFullName} ví dụ "Ng*** V** A"; {@code hasEmail} false thì không liên kết online được. */
    record LinkCandidate(Long customerId, String maskedFullName, boolean hasEmail) {}

    /** BR-TK-19, UC07: hồ sơ tại quầy chưa liên kết có SĐT trùng {@code phone}. */
    List<LinkCandidate> findLinkCandidates(String phone);
}
