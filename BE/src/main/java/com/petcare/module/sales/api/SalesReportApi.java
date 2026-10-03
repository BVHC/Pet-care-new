package com.petcare.module.sales.api;

import com.petcare.module.catalog.api.ServiceGroup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Owner: sales (BH, TG) · BE-2. Caller: report (UC89). Số liệu tính tại thời điểm xem (BR-BC-01).
 * {@code branchId} null = toàn chuỗi; kỳ [{@code from}, {@code to}] theo ngày giờ Việt Nam, đã được report kiểm.
 */
public interface SalesReportApi {

    record RevenueRow(Long branchId, OrderSource source, int orderCount, long amount) {}

    record LostRevenueRow(Long orderId, String orderCode, Long branchId, OrderSource source, long amount,
                          String reason, Instant cancelledAt) {}

    record ServiceCountRow(Long branchId, ServiceGroup group, Long serviceId, int count) {}

    /** BR-BC-02: Order PAID có {@code paid_at} trong kỳ, theo chi nhánh của Order, tách theo nguồn. */
    List<RevenueRow> revenue(Long branchId, LocalDate from, LocalDate to);

    /** BR-BC-02: thất thu = Order {@code cancel_type = UNPAID} hủy trong kỳ; CHECKOUT_ABORTED không tính. */
    List<LostRevenueRow> lostRevenue(Long branchId, LocalDate from, LocalDate to);

    /**
     * BR-BC-03 (2): lượt dịch vụ hoàn tất theo nhóm và theo dịch vụ = dòng SERVICE của Order VISIT có
     * {@code pending_at} trong kỳ ({@code pending_at} là lúc Visit#5 hoàn tất lượt).
     */
    List<ServiceCountRow> completedServiceCounts(Long branchId, LocalDate from, LocalDate to);
}
