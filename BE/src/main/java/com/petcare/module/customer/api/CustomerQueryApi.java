package com.petcare.module.customer.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owner: customer (KH) · BE-2. */
public interface CustomerQueryApi {

    /** Thông tin liên lạc để chọn kênh nhắc (BR-TB-02) và gửi thông báo. */
    record CustomerContact(Long customerId, String fullName, String phone, String email,
                           Long accountId, boolean linkDecisionPending) {}

    Optional<CustomerContact> findContact(Long customerId);

    /**
     * Nhiều hồ sơ một lần (appointment #3 ghép tên khách cho một trang lịch hẹn, docs/adr/0048). Thêm 15/10 (06 §6).
     * Nghĩa vụ như {@code PetQueryApi.findPets}: id không có thì bỏ qua, không trả null, không phân trang, mỗi id tối đa
     * một lần, thứ tự tùy ý, đọc mới mỗi lần gọi; bên gọi truyền tối đa 100 id; tập rỗng → danh sách rỗng.
     */
    List<CustomerContact> findContacts(Collection<Long> customerIds);

    /** Hồ sơ khách gắn với tài khoản (BR-KH-01: tài khoản khách luôn có hồ sơ). */
    Optional<Long> findCustomerIdByAccountId(Long accountId);

    /** {@code maskedFullName} ví dụ "Ng*** V** A"; {@code hasEmail} false thì không liên kết online được. */
    record LinkCandidate(Long customerId, String maskedFullName, boolean hasEmail) {}

    /** BR-TK-19, UC07: hồ sơ tại quầy chưa liên kết có SĐT trùng {@code phone}. Không có thì danh sách rỗng, không null. */
    List<LinkCandidate> findLinkCandidates(String phone);

    /** Tài khoản còn liên kết được vào hồ sơ tại quầy không (BR-TK-19, UC07; docs/adr/0027). */
    enum OnlineProfileLinkability {
        /** Hồ sơ của tài khoản là hồ sơ online chưa phát sinh dữ liệu. */
        LINKABLE,
        /** Hồ sơ của tài khoản không phải hồ sơ online: tài khoản đã liên kết hồ sơ tại quầy. */
        ALREADY_LINKED,
        /** Hồ sơ online đã có thú cưng, lịch hẹn, đặt chỗ, Order hoặc feedback. */
        HAS_DATA
    }

    /** BR-TK-19, UC07: đọc không khóa, để identity từ chối sớm; customer kiểm lại dưới khóa khi liên kết. */
    OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId);
}
