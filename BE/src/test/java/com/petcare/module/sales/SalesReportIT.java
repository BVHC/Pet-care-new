package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.api.SalesReportApi;
import com.petcare.module.sales.api.SalesReportApi.LostRevenueRow;
import com.petcare.module.sales.api.SalesReportApi.RevenueRow;
import com.petcare.module.sales.api.SalesReportApi.ServiceCountRow;

/**
 * {@code SalesReportApi} (BR-BC-02, BR-BC-03 (2); docs/adr/0087) trên Postgres 17 thật: doanh thu theo chi nhánh và nguồn,
 * ranh giới kỳ nửa mở theo giờ Việt Nam, thất thu chỉ gồm hủy {@code UNPAID}, lượt dịch vụ theo {@code pending_at}.
 * Dữ liệu chèn bằng SQL trên chi nhánh / khách riêng nên không đụng IT khác.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class SalesReportIT extends CashierItSupport {

    private static final LocalDate FROM = LocalDate.parse("2005-03-01");
    private static final LocalDate TO = LocalDate.parse("2005-03-31");
    /** 12:00 giờ Việt Nam ngày 15/03/2005, trong kỳ. */
    private static final Instant IN_PERIOD = Instant.parse("2005-03-15T05:00:00Z");

    @Autowired
    private SalesReportApi api;

    private long b1;
    private long b2;
    private long cashier1;
    private long cashier2;
    private final Map<Long, Long> shifts = new HashMap<>();

    @BeforeEach
    void setUp() {
        b1 = newBranch();
        b2 = newBranch();
        cashier1 = receptionist(b1);
        cashier2 = receptionist(b2);
    }

    private long shiftOf(long branch) {
        return shifts.computeIfAbsent(branch, b -> openShiftSql(b, b == b1 ? cashier1 : cashier2, false,
                LocalDate.parse("2005-03-15"), IN_PERIOD));
    }

    private long visitOf(long branch, long customer) {
        long pet = newPet(customer);
        return newVisit(branch, customer, pet, "MEDICAL", "WAITING", null, branch == b1 ? cashier1 : cashier2);
    }

    private long insertOrder(long branch, String source, String status, long amount, Instant pendingAt, Instant paidAt,
            Long paymentId, Instant cancelledAt, String cancelType, String cancelReason) {
        long customer = newCustomer();
        Long visit = source.equals("VISIT") ? visitOf(branch, customer) : null;
        long owner = branch == b1 ? cashier1 : cashier2;
        return jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, total_amount, created_by,
                                    pending_at, paid_at, payment_id, cancelled_at, cancel_type, cancel_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "DH-IT-" + UUID.randomUUID().toString().substring(0, 12), source, customer, branch,
                visit, status, amount, owner, ts(pendingAt), ts(paidAt), paymentId, ts(cancelledAt), cancelType,
                cancelReason);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private long paidOrderOf(long branch, String source, long amount, Instant paidAt) {
        long owner = branch == b1 ? cashier1 : cashier2;
        long customer = newCustomer();
        long payment = paymentSql(branch, customer, shiftOf(branch), "CASH", amount, owner);
        jdbc.update("UPDATE payments SET paid_at = ? WHERE id = ?", ts(paidAt), payment);
        Long visit = source.equals("VISIT") ? visitOf(branch, customer) : null;
        return jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, total_amount, created_by,
                                    pending_at, paid_at, payment_id)
                VALUES (?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "DH-IT-" + UUID.randomUUID().toString().substring(0, 12), source, customer, branch,
                visit, amount, owner, ts(paidAt), ts(paidAt), payment);
    }

    private void serviceLine(long orderId, long serviceId, long owner) {
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                                         added_by, owner_account_id)
                VALUES (?, 'SERVICE', ?, 'Dịch vụ IT', 1, 100000, 100000, ?, ?)""", orderId, serviceId, owner, owner);
    }

    @Test
    void revenueGroupsPaidOrdersByBranchAndSourceUsingTotalAmount() {
        paidOrderOf(b1, "VISIT", 100_000, IN_PERIOD);
        paidOrderOf(b1, "VISIT", 50_000, IN_PERIOD);
        paidOrderOf(b1, "RETAIL", 30_000, IN_PERIOD);
        insertOrder(b1, "RETAIL", "PENDING", 999, IN_PERIOD, null, null, null, null, null);
        paidOrderOf(b2, "RETAIL", 70_000, IN_PERIOD);

        assertThat(api.revenue(b1, FROM, TO)).containsExactly(
                new RevenueRow(b1, OrderSource.VISIT, 2, 150_000L),
                new RevenueRow(b1, OrderSource.RETAIL, 1, 30_000L));
        assertThat(api.revenue(null, FROM, TO)).contains(new RevenueRow(b2, OrderSource.RETAIL, 1, 70_000L));
    }

    @Test
    void paidAtBoundaryIsHalfOpenInVietnamTime() {
        paidOrderOf(b1, "RETAIL", 1, Instant.parse("2005-03-31T16:59:59.999999Z"));   // 23:59:59.999999 VN 31/03
        paidOrderOf(b1, "RETAIL", 10, Instant.parse("2005-03-31T17:00:00Z"));          // 00:00 VN 01/04
        paidOrderOf(b1, "RETAIL", 100, Instant.parse("2005-02-28T17:00:00Z"));         // 00:00 VN 01/03

        assertThat(api.revenue(b1, FROM, TO)).containsExactly(new RevenueRow(b1, OrderSource.RETAIL, 2, 101L));
    }

    @Test
    void lostRevenueCountsOnlyUnpaidCancellations() {
        insertOrder(b1, "VISIT", "CANCELLED", 40_000, IN_PERIOD, null, null, IN_PERIOD, "UNPAID", "Khách bỏ về");
        insertOrder(b1, "VISIT", "CANCELLED", 1, null, null, null,
                IN_PERIOD, "VISIT_CANCELLED", "Hủy lượt");
        insertOrder(b1, "RETAIL", "CANCELLED", 2, null, null, null, IN_PERIOD, "RETAIL_UNCLOSED", "Bỏ giỏ");
        insertOrder(b1, "VISIT", "CANCELLED", 3, null, null, null, IN_PERIOD, "CHECKOUT_ABORTED", "Hủy phiên trả");
        insertOrder(b1, "VISIT", "CANCELLED", 4, IN_PERIOD, null, null, Instant.parse("2005-04-01T00:00:00Z"), "UNPAID",
                "Ngoài kỳ");

        assertThat(api.lostRevenue(b1, FROM, TO)).singleElement().satisfies((LostRevenueRow row) -> {
            assertThat(row.amount()).isEqualTo(40_000L);
            assertThat(row.reason()).isEqualTo("Khách bỏ về");
            assertThat(row.source()).isEqualTo(OrderSource.VISIT);
            assertThat(row.branchId()).isEqualTo(b1);
            assertThat(row.cancelledAt()).isEqualTo(IN_PERIOD);
        });
    }

    @Test
    void completedServicesCountsServiceLinesOfVisitOrdersByPendingAt() {
        long s1 = newService("MEDICAL", 100_000);
        long pending = insertOrder(b1, "VISIT", "PENDING", 200_000, IN_PERIOD, null, null, null, null, null);
        serviceLine(pending, s1, cashier1);
        serviceLine(pending, s1, cashier1);
        long unpaid = insertOrder(b1, "VISIT", "CANCELLED", 100_000, IN_PERIOD, null, null, IN_PERIOD, "UNPAID", "x");
        serviceLine(unpaid, s1, cashier1);
        long cancelledVisit = insertOrder(b1, "VISIT", "CANCELLED", 100_000, null, null, null, IN_PERIOD,
                "VISIT_CANCELLED", "x");
        serviceLine(cancelledVisit, s1, cashier1);
        long outside = insertOrder(b1, "VISIT", "PENDING", 100_000, Instant.parse("2005-04-02T00:00:00Z"), null, null,
                null, null, null);
        serviceLine(outside, s1, cashier1);

        assertThat(api.completedServiceCounts(b1, FROM, TO))
                .containsExactly(new ServiceCountRow(b1, ServiceGroup.MEDICAL, s1, 3));
    }
}
