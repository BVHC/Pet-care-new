package com.petcare.module.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderRef;

/**
 * Task 127: {@code StockApi.consumeReservation} (Đơn hàng online#2, BR-BH-09) trên Postgres 17 thật. {@code HELD} →
 * {@code CONSUMED} cùng xuất FEFO từ lô chưa hết hạn với movement {@code SALE} nguồn {@code orders/<orderId>}; chỉ chạm
 * phần giữ của đúng đơn đó; gọi lại không xuất lần hai; thiếu hàng thì ném lỗi và không đổi gì. Chi nhánh / sản phẩm
 * riêng mỗi test, đồng hồ ứng dụng thật (cùng {@link StockHeldReservationIT}), nên không cần era riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StockConsumeReservationIT extends InventoryItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private StockApi stock;

    private long branch;
    private long actor;
    private long customerId;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> onlineOrderIds = new ArrayList<>();

    /** Order ONLINE + online_orders thật; {@code ids[0]} = online_orders.id, {@code ids[1]} = orders.id. */
    private record Ids(long online, long order) {
        OrderRef ref() {
            return new OrderRef(online, order);
        }
    }

    @BeforeEach
    void setUp() {
        branch = newBranch();
        actor = staff("BRANCH_MANAGER", branch);
        customerId = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khach xuat kho', '0901234567', 'COUNTER')
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void clean() {
        if (!onlineOrderIds.isEmpty()) {
            jdbc.update("DELETE FROM stock_reservations WHERE online_order_id IN (" + csv(onlineOrderIds) + ")");
            jdbc.update("DELETE FROM online_orders WHERE id IN (" + csv(onlineOrderIds) + ")");
        }
        if (!orderIds.isEmpty()) {
            jdbc.update("DELETE FROM orders WHERE id IN (" + csv(orderIds) + ")");
        }
        jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        onlineOrderIds.clear();
        orderIds.clear();
    }

    private static String csv(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    private static String inDays(int days) {
        return LocalDate.now(ZONE).plusDays(days).toString();
    }

    private Ids onlineOrder() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long orderId = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, total_amount, created_by, pending_at)
                VALUES (?, 'ONLINE', ?, ?, 'PENDING', 0, ?, now()) RETURNING id
                """, Long.class, "T127-" + tag, customerId, branch, actor);
        orderIds.add(orderId);
        long onlineId = jdbc.queryForObject("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name, receiver_phone,
                                           ship_address, shipping_fee, payment_due_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 'A', '0901234567', 'B', 0, now() + interval '1 day') RETURNING id
                """, Long.class, "T127-" + tag, orderId, customerId, branch);
        onlineOrderIds.add(onlineId);
        return new Ids(onlineId, orderId);
    }

    private void reserve(Ids ids, long productId, int quantity, String status) {
        jdbc.update("""
                INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status)
                VALUES (?, ?, ?, ?, ?)
                """, ids.online(), branch, productId, quantity, status);
    }

    private String statusOf(Ids ids, long productId) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE online_order_id = ? AND product_id = ?",
                String.class, ids.online(), productId);
    }

    private List<Map<String, Object>> movements(long lotId) {
        return jdbc.queryForList("SELECT * FROM stock_movements WHERE stock_lot_id = ? ORDER BY id", lotId);
    }

    private void inTx(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    @Test
    void consumesHeldFromNonExpiredLotsInFefoOrderAndWritesSaleMovements() {
        long product = newProduct("GOODS", true);
        long expired = lot(branch, product, "EXP", inDays(-1), 50, actor);
        long soon = lot(branch, product, "SOON", inDays(10), 3, actor);
        long later = lot(branch, product, "LATER", inDays(90), 10, actor);
        Ids order = onlineOrder();
        reserve(order, product, 5, "HELD");

        inTx(() -> stock.consumeReservation(order.ref(), actor));

        assertThat(statusOf(order, product)).isEqualTo("CONSUMED");
        assertThat(lotQuantity(expired)).as("lô hết hạn không bị xuất").isEqualTo(50);
        assertThat(lotQuantity(soon)).as("lô hết hạn sớm nhất xuất hết trước").isZero();
        assertThat(lotQuantity(later)).isEqualTo(8);
        List<Map<String, Object>> soonMoves = movements(soon);
        Map<String, Object> last = soonMoves.get(soonMoves.size() - 1);
        assertThat(last).containsEntry("movement_type", "SALE").containsEntry("quantity_delta", -3)
                .containsEntry("balance_after", 0).containsEntry("source_type", "orders")
                .containsEntry("source_id", order.order()).containsEntry("created_by", actor);
        List<Map<String, Object>> laterMoves = movements(later);
        assertThat(laterMoves.get(laterMoves.size() - 1)).containsEntry("movement_type", "SALE")
                .containsEntry("quantity_delta", -2).containsEntry("source_id", order.order());
        assertThat(movements(expired)).noneMatch(m -> "SALE".equals(m.get("movement_type")));
    }

    @Test
    void consumesEveryProductOfTheOrderInOneCall() {
        long first = newProduct("GOODS", false);
        long second = newProduct("GOODS", false);
        long lotA = defaultLot(branch, first, 10, actor);
        long lotB = defaultLot(branch, second, 10, actor);
        Ids order = onlineOrder();
        reserve(order, first, 4, "HELD");
        reserve(order, second, 6, "HELD");

        inTx(() -> stock.consumeReservation(order.ref(), actor));

        assertThat(lotQuantity(lotA)).isEqualTo(6);
        assertThat(lotQuantity(lotB)).isEqualTo(4);
        assertThat(statusOf(order, first)).isEqualTo("CONSUMED");
        assertThat(statusOf(order, second)).isEqualTo("CONSUMED");
    }

    @Test
    void touchesOnlyThatOrdersReservationAndLeavesOthersHeld() {
        long product = newProduct("GOODS", false);
        long lot = defaultLot(branch, product, 10, actor);
        Ids mine = onlineOrder();
        Ids other = onlineOrder();
        reserve(mine, product, 3, "HELD");
        reserve(other, product, 4, "HELD");

        inTx(() -> stock.consumeReservation(mine.ref(), actor));

        assertThat(statusOf(mine, product)).isEqualTo("CONSUMED");
        assertThat(statusOf(other, product)).isEqualTo("HELD");
        assertThat(lotQuantity(lot)).as("chỉ trừ phần của đơn này").isEqualTo(7);
        assertThat(movements(lot)).filteredOn(m -> "SALE".equals(m.get("movement_type"))).hasSize(1);
    }

    @Test
    void secondCallIsANoOpAndNeverIssuesTwice() {
        long product = newProduct("GOODS", false);
        long lot = defaultLot(branch, product, 10, actor);
        Ids order = onlineOrder();
        reserve(order, product, 3, "HELD");

        inTx(() -> stock.consumeReservation(order.ref(), actor));
        long movementsAfterFirst = movementCount(lot);
        inTx(() -> stock.consumeReservation(order.ref(), actor));

        assertThat(lotQuantity(lot)).isEqualTo(7);
        assertThat(movementCount(lot)).isEqualTo(movementsAfterFirst);
        assertThat(statusOf(order, product)).isEqualTo("CONSUMED");
    }

    @Test
    void releasedOrMissingReservationsIssueNothing() {
        long product = newProduct("GOODS", false);
        long lot = defaultLot(branch, product, 10, actor);
        Ids released = onlineOrder();
        reserve(released, product, 3, "RELEASED");
        Ids none = onlineOrder();
        long movementsBefore = movementCount(lot);

        inTx(() -> stock.consumeReservation(released.ref(), actor));
        inTx(() -> stock.consumeReservation(none.ref(), actor));

        assertThat(statusOf(released, product)).isEqualTo("RELEASED");
        assertThat(lotQuantity(lot)).isEqualTo(10);
        assertThat(movementCount(lot)).isEqualTo(movementsBefore);
    }

    @Test
    void shortageThrowsAndRollsBackEverything() {
        long enough = newProduct("GOODS", false);
        long scarce = newProduct("GOODS", true);
        long lotEnough = defaultLot(branch, enough, 10, actor);
        long lotScarce = lot(branch, scarce, "ONLY-EXPIRED", inDays(-2), 20, actor); // chỉ còn lô hết hạn
        Ids order = onlineOrder();
        reserve(order, enough, 4, "HELD");
        reserve(order, scarce, 5, "HELD");

        assertThatThrownBy(() -> inTx(() -> stock.consumeReservation(order.ref(), actor)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("không còn đủ lô");

        assertThat(statusOf(order, enough)).isEqualTo("HELD");
        assertThat(statusOf(order, scarce)).isEqualTo("HELD");
        assertThat(lotQuantity(lotEnough)).as("không trừ gì một phần").isEqualTo(10);
        assertThat(lotQuantity(lotScarce)).isEqualTo(20);
    }

    @Test
    void refusesToRunWithoutACallerTransactionAndRejectsMissingArguments() {
        Ids order = onlineOrder();

        assertThatThrownBy(() -> stock.consumeReservation(order.ref(), actor))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inTx(() -> stock.consumeReservation(order.ref(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> stock.consumeReservation(new OrderRef(order.online(), null), actor)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> stock.consumeReservation(null, actor)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
