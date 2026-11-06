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

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 3): placeholder dạng default — ném UnsupportedOperationException
    // tới khi CustomerQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /** Một địa chỉ trong sổ địa chỉ của khách (erd {@code addresses}); shop chốt snapshot từ đây lúc đặt hàng (BR-BH-08). */
    record AddressInfo(Long addressId, String receiverName, String receiverPhone, String addressLine, String ward,
                       String province) {}

    /**
     * BR-BH-08: địa chỉ {@code addressId} <b>của khách {@code customerId}</b>; không có hoặc của khách khác thì rỗng (bên
     * gọi trả 400 {@code BR-BH-08}, không lộ địa chỉ của người khác). Đọc không khóa. Bản thật trước 17/11 (T63).
     */
    default Optional<AddressInfo> findAddress(Long customerId, Long addressId) {
        throw placeholder("findAddress", "T63");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("CustomerQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
