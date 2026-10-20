package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.repository.OrderListRepository;
import com.petcare.module.sales.repository.OrderListRepository.Filter;
import com.petcare.module.sales.repository.OrderListRepository.Row;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Danh sách Order của chi nhánh (sales-v1 #2, UC66, UC70, ST13; BR-BH-05; docs/adr/0051). Nhân viên chi nhánh (A05–A08)
 * chỉ thấy chi nhánh mình; mọi bộ lọc AND. {@code date}: ngày tạo theo giờ Việt Nam. {@code pendingOverdue = true}:
 * Order {@code PENDING} chốt từ {@code order.pending_alert_days} [CFG] ngày nghiệp vụ trở về trước (mặc định 1 = chốt
 * trước 00:00 hôm nay), sắp chốt cũ nhất trước; {@code false} = không lọc. Mặc định sắp {@code created_at DESC, id DESC}.
 * {@code size} kẹp 1–100, {@code page} âm = 0. Một transaction readOnly + REPEATABLE READ để tổng số và trang cùng một
 * snapshot (như ADR-0038); không khóa.
 */
@Service
@RequiredArgsConstructor
public class OrderListService {

    static final int MAX_PAGE_SIZE = 100;

    private final OrderListRepository queries;
    private final CustomerQueryApi customers;
    private final SystemConfigApi configs;
    private final BranchScope branchScope;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OrderSummaryResponse> listOrders(OrderStatus status, OrderSource source, Long customerId,
            Long visitId, Long boardingBookingId, LocalDate date, Boolean pendingOverdue, int page, int size) {
        long branchId = branchScope.resolve(null);
        Instant createdFrom = date == null ? null : date.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant createdBefore = date == null ? null
                : date.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant pendingBefore = Boolean.TRUE.equals(pendingOverdue) ? overdueCutoff() : null;
        Filter filter = new Filter(branchId, status, source, customerId, visitId, boardingBookingId, createdFrom,
                createdBefore, pendingBefore);

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        long total = queries.count(filter);
        List<Row> rows = total == 0 ? List.of() : queries.findPage(filter, pageable.getOffset(), pageable.getPageSize());
        return PageResponse.of(new PageImpl<>(toSummaries(rows), pageable, total));
    }

    /** Chốt trước 00:00 của ngày (hôm nay − N + 1): N = 1 → chốt từ hôm qua trở về trước. */
    private Instant overdueCutoff() {
        int days = configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS);
        return LocalDate.now(clock).minusDays(days - 1L).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private List<OrderSummaryResponse> toSummaries(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerContact> contacts = customers.findContacts(rows.stream().map(Row::customerId).distinct()
                .toList()).stream().collect(Collectors.toMap(CustomerContact::customerId, Function.identity()));
        return rows.stream().map(row -> {
            CustomerContact contact = contacts.get(row.customerId());
            if (contact == null) {
                throw new IllegalStateException("Order #" + row.orderId() + " trỏ tới khách #" + row.customerId()
                        + " không tồn tại");
            }
            return new OrderSummaryResponse(row.orderId(), row.code(), row.source(), row.status(), row.customerId(),
                    contact.fullName(), row.branchId(), row.totalAmount(), row.createdAt(), row.pendingAt(),
                    row.paidAt());
        }).toList();
    }
}
