package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;

/**
 * {@code orders.version} (docs/adr/0132, trả D045): lưới an toàn khi một transaction nạp Order <b>không khóa</b> rồi mới
 * lấy khóa dòng trong cùng transaction, trong lúc transaction khác commit sửa Order. Khóa dòng vẫn là cơ chế chính
 * (ADR-0044); các test này chứng minh version bắt vi phạm quy ước "khóa trước, đọc sau" và không đổi hành vi khi quy
 * ước được giữ. Transaction chen vào ghi bằng JDBC nên tự tăng {@code version} như Hibernate sẽ làm.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SalesOrderVersionIT extends SalesItSupport {

    private static final long ADDED = 50_000;

    @Autowired
    private VisitOrderApi visitOrders;

    private TransactionTemplate tx;
    private long branch;
    private long receptionist;
    private long vet;
    private long customer;
    private long visit;
    private long examService;
    private long vaccine;
    private LineRef order;
    private long original;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        branch = newBranch();
        receptionist = staff("RECEPTIONIST", branch);
        vet = staff("VET", branch);
        customer = newCustomer();
        long pet = newPet(customer);
        visit = newVisit(branch, customer, pet, "MEDICAL", "WAITING", null, receptionist);
        examService = newService("MEDICAL", 150_000);
        vaccine = newPricedProduct("VACCINE", false, 200_000);
        order = tx.execute(s -> visitOrders.openForVisit(visit, branch, customer, examService, vet, receptionist));
        original = orderTotal(order.orderId());
    }

    /** Transaction khác thêm một dòng và tăng version, commit khi transaction A đang chờ khóa. */
    private Runnable concurrentLineAdded() {
        return () -> {
            jdbc.update("""
                    INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price,
                            line_total, added_by, owner_account_id)
                    VALUES (?, 'SERVICE', ?, 'Thêm lúc chờ', 1, ?, ?, ?, ?)""", order.orderId(), examService, ADDED,
                    ADDED, vet, vet);
            jdbc.update("UPDATE orders SET total_amount = total_amount + ?, version = version + 1 WHERE id = ?",
                    ADDED, order.orderId());
        };
    }

    private long version(long orderId) {
        return jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId);
    }

    /** D045: đọc không khóa ({@code findServiceIds}) trước khi lấy khóa → 409, không ghi đè tổng, Order không chốt. */
    @Test
    void readingTheOrderBeforeLockingFailsWith409InsteadOfOverwritingTheTotal() throws Exception {
        List<Callable<Object>> actions = List.of(() -> tx.execute(s -> {
            visitOrders.findServiceIds(visit);
            visitOrders.markPendingByVisit(visit);
            return null;
        }));

        List<Outcome> outcomes = whileOrderLocked(order.orderId(), concurrentLineAdded(), actions);

        assertThat(outcomes.get(0).error()).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(orderStatus(order.orderId())).as("rollback cả use case: Order không chốt").isEqualTo("OPEN");
        assertThat(orderTotal(order.orderId())).as("giữ tổng của transaction kia")
                .isEqualTo(original + ADDED).isEqualTo(sumOfLines(order.orderId()));
        assertThat(version(order.orderId())).as("chỉ transaction kia tăng version").isEqualTo(1);
    }

    /** Thứ tự thật của Visit#5 (ADR-0069): khóa và nạp trước, đọc sau → thấy dòng mới, tổng đúng, không lỗi. */
    @Test
    void lockingBeforeReadingStaysCorrectWhenAnotherTransactionChangedTheOrderMeanwhile() throws Exception {
        List<Callable<Object>> actions = List.of(() -> tx.execute(s -> {
            visitOrders.markPendingByVisit(visit);
            return visitOrders.findServiceIds(visit);
        }));

        List<Outcome> outcomes = whileOrderLocked(order.orderId(), concurrentLineAdded(), actions);

        assertThat(outcomes.get(0).error()).isNull();
        assertThat(outcomes.get(0).value()).as("đọc sau khóa thấy cả dòng thêm lúc chờ")
                .isEqualTo(List.of(examService, examService));
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
        assertThat(orderTotal(order.orderId())).isEqualTo(original + ADDED).isEqualTo(sumOfLines(order.orderId()));
        assertThat(version(order.orderId())).as("transaction kia +1, markPending +1").isEqualTo(2);
    }

    /**
     * Chỉ thêm / xóa dòng mà tổng không đổi (dòng giá 0đ) vẫn làm tăng version: collection của Order là phía sở hữu
     * nên Hibernate tăng version của cha. Nếu test này đỏ thì khoảng hở của lưới an toàn rộng hơn ADR-0132 ghi.
     */
    @Test
    void addingAndRemovingAZeroPricedLineStillBumpsTheVersion() {
        jdbc.update("UPDATE products SET price = 0 WHERE id = ?", vaccine);
        long v0 = version(order.orderId());

        LineRef free = tx.execute(s -> visitOrders.addVaccineLine(visit, vaccine, vet));
        assertThat(orderTotal(order.orderId())).as("tổng không đổi").isEqualTo(original);
        assertThat(version(order.orderId())).isEqualTo(v0 + 1);

        tx.executeWithoutResult(s -> visitOrders.removeVaccineLine(free.orderLineId()));
        assertThat(orderTotal(order.orderId())).isEqualTo(original);
        assertThat(version(order.orderId())).isEqualTo(v0 + 2);
    }

    /** Luồng thường (khóa trước đọc sau, không ai chen vào) không bao giờ gặp lỗi version. */
    @Test
    void theNormalFlowNeverHitsAVersionConflict() {
        tx.executeWithoutResult(s -> visitOrders.addVaccineLine(visit, vaccine, vet));
        tx.executeWithoutResult(s -> visitOrders.markPendingByVisit(visit));

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING");
        assertThat(orderTotal(order.orderId())).isEqualTo(original + 200_000).isEqualTo(sumOfLines(order.orderId()));
    }
}
