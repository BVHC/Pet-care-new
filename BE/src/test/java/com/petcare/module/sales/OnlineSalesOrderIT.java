package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.api.OnlineSalesOrderApi;
import com.petcare.module.sales.api.OnlineSalesOrderApi.OnlineOrderLineInput;
import com.petcare.module.sales.job.PendingOrderDigestJob;
import com.petcare.support.MutableClock;

/**
 * Order nguồn ONLINE (task 117, Order#11) trên Postgres 17 thật: {@link OnlineSalesOrderApi#createPendingOrder} ghi
 * PENDING + {@code pending_at} + dòng GOODS giá chụp; quầy không thu, không hủy, ST13 và danh sách chờ thu bỏ qua.
 * Dùng đồng hồ IT thu ngân (năm 2005) như {@link PaymentIT}, chi nhánh / khách riêng nên không đụng IT khác.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class OnlineSalesOrderIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    private static final Instant OPENED = Instant.parse("2005-06-15T01:00:00Z");
    private static final Instant OVERDUE = Instant.parse("2005-06-14T10:00:00Z");

    @Autowired
    private MutableClock clock;
    @Autowired
    private OnlineSalesOrderApi onlineOrders;
    @Autowired
    private PendingOrderDigestJob digest;
    private TransactionTemplate tx;

    private long branch;
    private long receptionist;
    private long manager;
    private long customer;
    private long goods;
    private long cheap;
    private long shift;
    private String tReceptionist;
    private String tManager;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        tReceptionist = token(receptionist);
        tManager = token(manager);
        customer = newCustomer();
        shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        goods = newPricedProduct("GOODS", false, 100_000);
        cheap = newPricedProduct("GOODS", false, 50_000);
        defaultLot(branch, goods, 10, manager);
    }

    private long place(OnlineOrderLineInput... lines) {
        String code = "DH-IT-" + UUID.randomUUID().toString().substring(0, 12);
        return tx.execute(s -> onlineOrders.createPendingOrder(code, branch, customer, manager, List.of(lines)));
    }

    private long placeOne() {
        return place(new OnlineOrderLineInput(goods, "Hạt", 2, 90_000L));
    }

    private Map<String, Object> order(long id) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", id);
    }

    @Test
    void createPendingOrderWritesPendingGoodsLinesWithTheGivenSnapshotPricesAndTotal() {
        long id = place(new OnlineOrderLineInput(goods, "Hạt A", 2, 90_000L),
                new OnlineOrderLineInput(cheap, "Cát B", 3, 45_000L));

        Map<String, Object> row = order(id);
        assertThat(row).containsEntry("source", "ONLINE").containsEntry("status", "PENDING")
                .containsEntry("customer_id", customer).containsEntry("branch_id", branch)
                .containsEntry("created_by", manager).containsEntry("total_amount", 315_000L)
                .containsEntry("visit_id", null).containsEntry("boarding_booking_id", null)
                .containsEntry("payment_id", null);
        assertThat(row.get("pending_at")).isNotNull();
        assertThat(((java.sql.Timestamp) row.get("pending_at")).toInstant())
                .isEqualTo(CashierClockConfig.START.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT * FROM order_lines WHERE order_id = ? ORDER BY id",
                id);
        assertThat(lines).extracting(l -> l.get("line_type")).containsOnly("GOODS");
        assertThat(lines).extracting(l -> l.get("product_id")).containsExactly(goods, cheap);
        assertThat(lines).extracting(l -> l.get("description")).containsExactly("Hạt A", "Cát B");
        assertThat(lines).extracting(l -> l.get("unit_price")).containsExactly(90_000L, 45_000L);
        assertThat(lines).extracting(l -> l.get("line_total")).containsExactly(180_000L, 135_000L);
        assertThat(lines).extracting(l -> l.get("is_auto_generated")).containsOnly(false);
    }

    @Test
    void createPendingOrderRefusesToRunWithoutACallerTransaction() {
        assertThatThrownBy(() -> onlineOrders.createPendingOrder("DH-X", branch, customer, manager,
                List.of(new OnlineOrderLineInput(goods, "Hạt", 1, 1L))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void cashierCannotCollectAnOnlineOrderAndNothingIsDeducted() {
        long online = placeOne();
        long before = stockOf(branch, goods);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(online), "method", "CASH", "amount", 180_000));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(order(online)).containsEntry("status", "PENDING").containsEntry("payment_id", null);
        assertThat(stockOf(branch, goods)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE branch_id = ?", Long.class, branch))
                .isZero();
    }

    @Test
    void onlineOrderMixedWithACounterOrderRejectsTheWholeCollection() {
        long online = placeOne();
        long counter = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(counter, online), "method", "CASH", "amount", 280_000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(order(counter)).containsEntry("status", "PENDING");
        assertThat(order(online)).containsEntry("status", "PENDING");
    }

    @Test
    void paymentPreviewAndPayableListIgnoreOnlineOrders() {
        long online = placeOne();
        long counter = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));

        ResponseEntity<Map<String, Object>> preview = call(HttpMethod.POST, "/api/payments/preview", tReceptionist,
                Map.of("orderIds", List.of(online)));
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) preview.getBody().get("message")).endsWith("(BR-TG-01)");

        ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET,
                "/api/customers/" + customer + "/payable-orders", tReceptionist, null);
        if (list.getStatusCode() == HttpStatus.OK) {
            assertThat(list.getBody().toString()).contains("orderId=" + counter).doesNotContain("orderId=" + online);
        }
    }

    @Test
    void branchManagerCannotCancelAnOnlinePendingOrder() {
        long online = placeOne();

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST,
                "/api/orders/" + online + "/cancel-unpaid", tManager, Map.of("reason", "Khách không trả"));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(order(online)).containsEntry("status", "PENDING").containsEntry("cancel_type", null);
    }

    @Test
    void receptionistCannotAbortOrCancelAnOnlineOrderEither() {
        long online = placeOne();

        assertThat(call(HttpMethod.POST, "/api/orders/" + online + "/abort-checkout", tReceptionist,
                Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/orders/" + online + "/cancel", tReceptionist,
                Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(order(online)).containsEntry("status", "PENDING");
    }

    @Test
    void st13DigestAndTheOverdueListIgnoreOnlineOrders() {
        long online = placeOne();
        jdbc.update("UPDATE orders SET pending_at = ? WHERE id = ?", java.sql.Timestamp.from(OVERDUE), online);

        digest.run();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE template_code = 'PENDING_ORDERS_DIGEST_APP' AND recipient_account_id = ?""", Long.class,
                manager)).isZero();
        Map<String, Object> page = data(call(HttpMethod.GET, "/api/orders?pendingOverdue=true", tManager, null),
                HttpStatus.OK);
        assertThat(page.get("totalElements")).isEqualTo(0);

        long counter = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        jdbc.update("UPDATE orders SET pending_at = ? WHERE id = ?", java.sql.Timestamp.from(OVERDUE), counter);
        digest.run();
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'so_order' FROM notification_outbox
                WHERE template_code = 'PENDING_ORDERS_DIGEST_APP' AND recipient_account_id = ?""", String.class,
                manager)).isEqualTo("1");
    }

    @Test
    void staffCanStillReadAnOnlineOrderInTheListAndDetail() {
        long online = placeOne();

        Map<String, Object> detail = data(call(HttpMethod.GET, "/api/orders/" + online, tManager, null),
                HttpStatus.OK);
        assertThat(detail).containsEntry("source", "ONLINE").containsEntry("status", "PENDING");
        Map<String, Object> page = data(call(HttpMethod.GET, "/api/orders?source=ONLINE", tManager, null),
                HttpStatus.OK);
        assertThat(page.get("totalElements")).isEqualTo(1);
    }

    @Test
    void receptionistCannotAddEditOrDeleteLinesOfAnOnlineOrder() {
        long online = placeOne();
        long lineId = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, online);

        assertThat(call(HttpMethod.POST, "/api/orders/" + online + "/lines", tReceptionist,
                Map.of("lineType", "GOODS", "productId", cheap, "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PATCH, "/api/orders/" + online + "/lines/" + lineId, tReceptionist,
                Map.of("quantity", 1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.DELETE, "/api/orders/" + online + "/lines/" + lineId, tReceptionist, null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(order(online)).containsEntry("total_amount", 180_000L);
        assertThat(jdbc.queryForList("SELECT quantity FROM order_lines WHERE order_id = ?", Integer.class, online))
                .containsExactly(2);
    }

    @Test
    void moveToExternalPurchaseRejectsAnOnlineOrder() {
        long online = placeOne();
        long lineId = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, online);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST,
                "/api/orders/" + online + "/lines/" + lineId + "/move-to-external-purchase", tReceptionist, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-BH-04)");
    }

    @Test
    void createPendingOrderRefusesNullFields() {
        assertThatThrownBy(() -> tx.execute(s -> onlineOrders.createPendingOrder("DH-X", branch, customer, manager,
                List.of(new OnlineOrderLineInput(null, "Hạt", 1, 1L))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(s -> onlineOrders.createPendingOrder(null, branch, customer, manager,
                List.of(new OnlineOrderLineInput(goods, "Hạt", 1, 1L))))).isInstanceOf(IllegalArgumentException.class);
    }
}
