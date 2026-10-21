package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xem trước phiếu thu, Order chờ thu của khách, chi tiết phiếu thu (sales-v1 #12, #13, #15; UC70; docs/adr/0064) trên
 * Postgres 17 thật qua HTTP: đọc thuần (không đổi {@code xmin}), dòng thiếu tồn khớp lý do từ chối khi thu, phạm vi chi
 * nhánh và vai trò.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class PaymentQueryIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    private static final Instant OPENED = Instant.parse("2005-06-15T01:00:00Z");

    @Autowired
    private MutableClock clock;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long customer;
    private long goods;
    private String tReceptionist;
    private String tManager;
    private String tVet;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        tReceptionist = token(receptionist);
        tManager = token(manager);
        tVet = token(staff("VET", branch));
        customer = newCustomer();
        openShiftSql(branch, receptionist, false, DAY, OPENED);
        goods = newPricedProduct("GOODS", false, 100_000);
        defaultLot(branch, goods, 4, manager);
    }

    private ResponseEntity<Map<String, Object>> preview(String token, List<Long> ids) {
        return call(HttpMethod.POST, "/api/payments/preview", token, Map.of("orderIds", ids));
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, HttpStatus status, String rule) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat((String) response.getBody().get("message")).endsWith("(" + rule + ")");
    }

    private String xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM " + table + " WHERE id = ?", String.class, id);
    }

    // ---------------------------------------------------------------- #13

    @Test
    void previewShowsTotalAndEveryLineOfAShortProductWithoutWriting() {
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 3, 100_000));
        long b = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 2, 100_000));
        long lot = jdbc.queryForObject("""
                SELECT l.id FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND i.product_id = ?""", Long.class, branch, goods);
        String orderXmin = xmin("orders", a);
        String lotXmin = xmin("stock_lots", lot);

        Map<String, Object> preview = data(preview(tReceptionist, List.of(a, b)), HttpStatus.OK);

        assertThat(preview).containsOnlyKeys("customerId", "branchId", "totalAmount", "shortages")
                .containsEntry("totalAmount", 500_000).containsEntry("customerId", (int) customer)
                .containsEntry("branchId", (int) branch);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shortages = (List<Map<String, Object>>) preview.get("shortages");
        assertThat(shortages).hasSize(2);
        assertThat(shortages).extracting(s -> s.get("orderId")).containsExactly((int) a, (int) b);
        assertThat(shortages.get(0)).containsOnlyKeys("orderId", "lineId", "lineType", "productId", "productName",
                "requested", "available").containsEntry("requested", 3).containsEntry("available", 4)
                .containsEntry("productName", "Hạt");
        assertThat(shortages.get(1)).containsEntry("requested", 2).containsEntry("available", 4);
        assertThat(xmin("orders", a)).isEqualTo(orderXmin);
        assertThat(xmin("stock_lots", lot)).isEqualTo(lotXmin);
    }

    @Test
    void previewWithEnoughStockHasNoShortagesAndMatchesTheRealCollection() {
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 2, 100_000));

        Map<String, Object> preview = data(preview(tReceptionist, List.of(a)), HttpStatus.OK);

        assertThat(preview.get("shortages")).isEqualTo(List.of());
        data(call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(a), "method", "CASH", "amount", preview.get("totalAmount"))),
                HttpStatus.CREATED);
    }

    @Test
    void previewDoesNotNeedAnOpenShift() {
        jdbc.update("DELETE FROM cashier_shifts WHERE cashier_id = ?", receptionist);
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));

        data(preview(tReceptionist, List.of(a)), HttpStatus.OK);
    }

    @Test
    void previewErrors() {
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        long other = pendingOrderSql(branch, newCustomer(), receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        long foreign = pendingOrderSql(otherBranch, customer, receptionist(otherBranch),
                LineSpec.goods(goods, "Hạt", 1, 100_000));
        long paid = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        data(call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(paid), "method", "CASH", "amount", 100_000)), HttpStatus.CREATED);

        assertThat(preview(tReceptionist, List.of(a, Long.MAX_VALUE)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(preview(tReceptionist, List.of(foreign)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertRule(preview(tReceptionist, List.of(a, other)), HttpStatus.BAD_REQUEST, "BR-TG-03");
        assertRule(preview(tReceptionist, List.of(paid)), HttpStatus.BAD_REQUEST, "BR-TG-01");
        assertThat(preview(tReceptionist, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(preview(tManager, List.of(a)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------- #12

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> payable(String token, long customerId) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
                "/api/customers/" + customerId + "/payable-orders", token, null);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @Test
    void payableOrdersAreOnlyPendingOfTheOwnBranchOldestFirst() {
        long first = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        long second = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        jdbc.update("UPDATE orders SET pending_at = now() - interval '2 hours' WHERE id = ?", first);
        jdbc.update("UPDATE orders SET pending_at = now() - interval '5 hours' WHERE id = ?", second);
        long foreign = pendingOrderSql(otherBranch, customer, receptionist(otherBranch),
                LineSpec.goods(goods, "Hạt", 1, 100_000));
        long open = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        jdbc.update("UPDATE orders SET status = 'OPEN', pending_at = NULL WHERE id = ?", open);

        List<Map<String, Object>> orders = payable(tReceptionist, customer);

        assertThat(orders).extracting(o -> ((Number) o.get("orderId")).longValue()).containsExactly(second, first);
        assertThat(orders).extracting(o -> o.get("orderId")).doesNotContain((int) foreign, (int) open);
        assertThat(orders.get(0)).containsEntry("status", "PENDING").containsEntry("totalAmount", 100_000);
    }

    @Test
    void payableOrdersOfAnUnknownCustomerIs404AndOtherRolesAre403() {
        assertThat(call(HttpMethod.GET, "/api/customers/" + Long.MAX_VALUE + "/payable-orders", tReceptionist, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/customers/" + customer + "/payable-orders", tManager, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(payable(tReceptionist, newCustomer())).isEmpty();
    }

    // ---------------------------------------------------------------- #15

    @Test
    void getPaymentMatchesTheCollectResponse() {
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        Map<String, Object> created = data(call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(a), "method", "TRANSFER", "amount", 100_000)), HttpStatus.CREATED);
        long paymentId = ((Number) created.get("paymentId")).longValue();

        Map<String, Object> viewed = data(call(HttpMethod.GET, "/api/payments/" + paymentId, tReceptionist, null),
                HttpStatus.OK);

        assertThat(viewed).isEqualTo(created);
        assertThat(data(call(HttpMethod.GET, "/api/payments/" + paymentId, tManager, null), HttpStatus.OK))
                .isEqualTo(created);
    }

    @Test
    void getPaymentRules() {
        long a = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        long paymentId = ((Number) data(call(HttpMethod.POST, "/api/payments", tReceptionist,
                Map.of("orderIds", List.of(a), "method", "CASH", "amount", 100_000)), HttpStatus.CREATED)
                .get("paymentId")).longValue();
        String tOutsider = token(receptionist(otherBranch));

        assertThat(call(HttpMethod.GET, "/api/payments/" + paymentId, tOutsider, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/payments/" + paymentId, tVet, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/payments/" + Long.MAX_VALUE, tReceptionist, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
