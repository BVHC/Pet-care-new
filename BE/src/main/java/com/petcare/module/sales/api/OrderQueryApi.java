package com.petcare.module.sales.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * Order nguồn VISIT của các lượt, theo lô: {@code visitId → orderId}, mọi trạng thái Order (Visit#6 để lại Order
     * CANCELLED). Lượt không có Order thì không có khóa trong map. Một câu SQL bất kể số id; tập rỗng → map rỗng, không
     * chạy SQL; bên gọi truyền tối đa 100 id và đã tự phân quyền. Đọc không khóa, chạy trong transaction của bên gọi.
     * Thêm 2026-10-20 bởi BE-1 cho hàng đợi visit (visit-v1 #2–4, docs/adr/0060).
     */
    Map<Long, Long> findOrderIdsByVisitIds(Collection<Long> visitIds);
}
