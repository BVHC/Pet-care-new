package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ReturnRequestCreateIT.TestBeans.class})
class ReturnRequestCreateIT extends CashierItSupport {

    private static final Instant NOW = Instant.parse("2050-06-15T18:00:00Z");
    private static final String PATH = "/api/return-requests";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MutableClock clock;
    private long branch;
    private long actor;
    private long manager;
    private long customer;
    private long goods;
    private long shift;
    private String bearer;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        branch = newBranch();
        actor = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        customer = newCustomer();
        goods = newPricedProduct("GOODS", false, 25_000);
        shift = openShiftSql(branch, actor, false, NOW.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(),
                NOW.minusSeconds(60));
        bearer = token(actor);
    }

    @AfterEach
    void cleanupReturns() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ?", manager);
        jdbc.update("DELETE FROM notifications WHERE account_id = ?", manager);
        jdbc.update("DELETE FROM return_request_lines WHERE request_id IN "
                + "(SELECT id FROM return_requests WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM return_requests WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM online_orders WHERE branch_id = ?", branch);
    }

    @Test
    void createsPendingRequestWithSnapshotAndManagerNotice() {
        long order = counterOrder(NOW.minus(1, ChronoUnit.DAYS), LineSpec.goods(goods, "Tên lúc bán", 3, 25_000));
        long line = lineId(order);
        Map<String, Object> created = data(create(bearer, body(order, line, 2)), HttpStatus.CREATED);

        long id = number(created.get("requestId"));
        assertThat(created).containsEntry("status", "PENDING").containsEntry("reasonCode", "DEFECTIVE")
                .containsEntry("reasonNote", "Hộp bị rách").containsEntry("createdAt", NOW.toString());
        assertThat(number(created.get("orderId"))).isEqualTo(order);
        assertThat(number(created.get("branchId"))).isEqualTo(branch);
        assertThat(created.get("code").toString()).matches("TH-500616-\\d{4,10}");
        assertThat(map(((List<?>) created.get("lines")).getFirst())).containsEntry("productName", "Tên lúc bán")
                .containsEntry("quantity", 2).containsEntry("unitPrice", 25_000);
        assertThat(jdbc.queryForObject("SELECT created_by FROM return_requests WHERE id = ?", Long.class, id))
                .isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT quantity FROM return_request_lines WHERE request_id = ?",
                Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT recipient_account_id FROM notification_outbox
                WHERE template_code = 'RETURN_REQUESTED_APP' AND payload ->> 'ma_tra_hang' = ?
                  AND payload ->> 'ma_don' IS NOT NULL AND payload ->> 'chi_nhanh' IS NOT NULL
                """, Long.class, created.get("code"))).containsExactly(manager);
    }

    @Test
    void rejectsMissingAuthWrongRoleAndAnotherBranch() {
        long order = counterOrder(NOW, LineSpec.goods(goods, "Hàng", 1, 25_000));
        Map<String, Object> input = body(order, lineId(order), 1);
        assertThat(create(null, input).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(create(token(manager), input).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create(token(receptionist(newBranch())), input).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(requestCount(order)).isZero();
    }

    @Test
    void rejectsInvalidLinesReasonAndUnpaidOrder() {
        long drug = newPricedProduct("DRUG", true, 25_000);
        long order = counterOrder(NOW, LineSpec.goods(drug, "Thuốc", 1, 25_000));
        assertRule(create(bearer, body(order, lineId(order), 1)), "BR-BH-13");
        assertThat(create(bearer, body(order, Long.MAX_VALUE, 1)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(create(bearer, body(Long.MAX_VALUE, lineId(order), 1)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        long valid = counterOrder(NOW, LineSpec.goods(goods, "Hàng", 2, 25_000));
        long line = lineId(valid);
        assertRule(create(bearer, Map.of("orderId", valid, "lines", List.of(line(line, 1)))), "BR-BH-13");
        assertRule(create(bearer, Map.of("orderId", valid, "lines", List.of(line(line, 1), line(line, 1)),
                "reasonCode", "DEFECTIVE")), "BR-BH-13");
        assertRule(create(bearer, body(valid, line, 3)), "BR-BH-13");
        assertThat(create(bearer, body(valid, line, 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long unpaid = pendingOrderSql(branch, customer, actor, LineSpec.goods(goods, "Hàng", 1, 25_000));
        assertRule(create(bearer, body(unpaid, lineId(unpaid), 1)), "BR-BH-13");
        assertThat(requestCount(valid)).isZero();
    }

    @Test
    void enforcesCounterDeadlineAndUsesDeliveryDateForOnlineOrder() {
        long oldCounter = counterOrder(NOW.minus(8, ChronoUnit.DAYS), LineSpec.goods(goods, "Hàng", 1, 25_000));
        assertRule(create(bearer, body(oldCounter, lineId(oldCounter), 1)), "BR-BH-13");

        long online = pendingOrderSql(branch, customer, actor, LineSpec.goods(goods, "Hàng", 1, 25_000));
        Timestamp paid = Timestamp.from(NOW.minus(10, ChronoUnit.DAYS));
        jdbc.update("UPDATE orders SET source = 'ONLINE', status = 'PAID', paid_at = ? WHERE id = ?", paid, online);
        jdbc.update("""
                INSERT INTO online_orders (code, order_id, customer_id, branch_id, status, receiver_name,
                                           receiver_phone, ship_address, shipping_fee, payment_due_at, paid_at)
                VALUES (?, ?, ?, ?, 'PAID', 'Khách', '0901234567', 'Địa chỉ', 0, ?, ?)
                """, "DH-IT-" + online, online, customer, branch, paid, paid);
        assertRule(create(bearer, body(online, lineId(online), 1)), "BR-BH-13");

        Timestamp delivered = Timestamp.from(NOW.minus(1, ChronoUnit.DAYS));
        jdbc.update("""
                UPDATE online_orders SET status = 'DELIVERED', shipped_at = ?, delivered_at = ?, carrier = 'GHN',
                                         tracking_code = 'IT'
                WHERE order_id = ?
                """, delivered, delivered, online);
        data(create(bearer, body(online, lineId(online), 1)), HttpStatus.CREATED);
    }

    @Test
    void concurrentRequestsCannotClaimTheSameLastUnit() throws Exception {
        long order = counterOrder(NOW, LineSpec.goods(goods, "Hàng", 1, 25_000));
        Map<String, Object> input = body(order, lineId(order), 1);
        List<Outcome> outcomes = whileOrderLocked(order, () -> {},
                List.of(() -> create(bearer, input).getStatusCode(), () -> create(bearer, input).getStatusCode()));
        assertThat(outcomes).extracting(Outcome::error).containsOnlyNulls();
        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(HttpStatus.CREATED,
                HttpStatus.BAD_REQUEST);
        assertThat(requestCount(order)).isEqualTo(1);
    }

    private long counterOrder(Instant paidAt, LineSpec... lines) {
        long order = pendingOrderSql(branch, customer, actor, lines);
        long payment = paymentSql(branch, customer, shift, "CASH", orderTotal(order), actor);
        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ?, payment_id = ? WHERE id = ?",
                Timestamp.from(paidAt), payment, order);
        return order;
    }

    private long lineId(long order) {
        return jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, order);
    }

    private long requestCount(long order) {
        return jdbc.queryForObject("SELECT count(*) FROM return_requests WHERE order_id = ?", Long.class, order);
    }

    private static Map<String, Object> body(long order, long lineId, int quantity) {
        return Map.of("orderId", order, "lines", List.of(line(lineId, quantity)), "reasonCode", "DEFECTIVE",
                "reasonNote", " Hộp bị rách ");
    }

    private static Map<String, Object> line(long id, int quantity) {
        return Map.of("orderLineId", id, "quantity", quantity);
    }

    private ResponseEntity<Map<String, Object>> create(String jwt, Map<String, Object> input) {
        return call(HttpMethod.POST, PATH, jwt, input);
    }
}
