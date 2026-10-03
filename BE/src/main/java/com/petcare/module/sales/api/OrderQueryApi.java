package com.petcare.module.sales.api;

import java.util.List;

/** Owner: sales (BH) · BE-2. */
public interface OrderQueryApi {

    /** BR-TK-19: hồ sơ online đã phát sinh Order thì không liên kết được. */
    boolean existsByCustomer(Long customerId);

    /**
     * BR-KH-08: thú có Order PENDING (nguồn VISIT hoặc BOARDING) thì không chuyển chủ.
     * Sales tự lấy visitId / bookingId của thú qua VisitQueryApi, BoardingQueryApi.
     */
    boolean hasPendingOrderForPet(Long petId);

    record UnpaidOrderRef(Long orderId, Long branchId) {}

    /** Order OPEN/PENDING của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<UnpaidOrderRef> findUnpaidOrdersByCustomer(Long customerId);
}
