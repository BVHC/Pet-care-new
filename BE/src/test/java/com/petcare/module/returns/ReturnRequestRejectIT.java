package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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
@Import({TestcontainersConfiguration.class, ReturnRequestRejectIT.TestBeans.class})
class ReturnRequestRejectIT extends CashierItSupport {

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
    private long receptionist;
    private long manager;
    private long order;
    private long line;
    private long requestId;
    private String receptionistToken;
    private String managerToken;
    private String requestCode;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        branch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        long customer = newCustomer();
        long product = newPricedProduct("GOODS", false, 25_000);
        order = pendingOrderSql(branch, customer, receptionist, LineSpec.goods(product, "Hàng trả", 1, 25_000));
        line = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, order);
        long shift = openShiftSql(branch, receptionist, false, NOW.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(),
                NOW.minusSeconds(60));
        long payment = paymentSql(branch, customer, shift, "CASH", orderTotal(order), receptionist);
        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = ?, payment_id = ? WHERE id = ?",
                Timestamp.from(NOW), payment, order);
        receptionistToken = token(receptionist);
        managerToken = token(manager);
        Map<String, Object> created = data(call(HttpMethod.POST, PATH, receptionistToken, Map.of(
                "orderId", order, "reasonCode", "DEFECTIVE", "lines", List.of(Map.of("orderLineId", line, "quantity", 1)))),
                HttpStatus.CREATED);
        requestId = number(created.get("requestId"));
        requestCode = created.get("code").toString();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (?, ?)", receptionist, manager);
        jdbc.update("DELETE FROM notifications WHERE account_id IN (?, ?)", receptionist, manager);
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'return_requests' AND entity_id = ?", requestId);
        jdbc.update("DELETE FROM return_request_lines WHERE request_id = ?", requestId);
        jdbc.update("DELETE FROM return_requests WHERE id = ?", requestId);
    }

    @Test
    void rejectsOnceAndCommitsDecisionAuditAndNoticeTogether() {
        Instant decidedAt = NOW.plusSeconds(60);
        clock.set(decidedAt);
        Map<String, Object> result = data(reject(requestId, managerToken, Map.of("reason", "  Sai chứng từ  ")),
                HttpStatus.OK);

        assertThat(result).containsEntry("code", requestCode).containsEntry("status", "REJECTED")
                .containsEntry("reasonCode", "DEFECTIVE").containsEntry("decisionNote", "Sai chứng từ")
                .containsEntry("createdAt", NOW.toString()).containsEntry("decidedAt", decidedAt.toString());
        assertThat(number(map(((List<?>) result.get("lines")).getFirst()).get("orderLineId"))).isEqualTo(line);
        assertThat(jdbc.queryForMap("SELECT status, decided_by, decision_note FROM return_requests WHERE id = ?", requestId))
                .containsEntry("status", "REJECTED").containsEntry("decided_by", manager)
                .containsEntry("decision_note", "Sai chứng từ");

        assertError(reject(requestId, managerToken, Map.of("reason", "Lý do khác")), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForList("""
                SELECT reason FROM audit_logs WHERE action = 'RETURN_REQUEST_REJECTED'
                  AND entity_type = 'return_requests' AND entity_id = ? AND actor_account_id = ?
                  AND before_data ->> 'status' = 'PENDING' AND after_data ->> 'status' = 'REJECTED'
                """, String.class, requestId, manager)).containsExactly("Sai chứng từ");
        assertThat(jdbc.queryForList("""
                SELECT payload ->> 'ly_do' FROM notification_outbox
                WHERE template_code = 'RETURN_REJECTED_APP' AND recipient_account_id = ?
                  AND payload ->> 'ma_tra_hang' = ? AND payload ->> 'ma_don' IS NOT NULL
                """, String.class, receptionist, requestCode)).containsExactly("Sai chứng từ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE return_request_id = ?", Long.class,
                requestId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movements movement
                JOIN stock_lots lot ON lot.id = movement.stock_lot_id
                JOIN inventory_items item ON item.id = lot.inventory_item_id
                WHERE item.branch_id = ?
                """, Long.class, branch)).isZero();
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    @Test
    void requiresReasonAndLeavesRequestPending() {
        assertRule(reject(requestId, managerToken, Map.of()), "BR-BH-14");
        assertRule(reject(requestId, managerToken, Map.of("reason", "   ")), "BR-BH-14");
        assertThat(reject(requestId, managerToken, Map.of("reason", "x".repeat(301))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertPending();
    }

    @Test
    void enforcesRoleAndBranchAndReportsMissingRequest() {
        Map<String, String> input = Map.of("reason", "Sai chứng từ");
        assertThat(reject(requestId, null, input).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reject(requestId, receptionistToken, input).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String otherManager = token(staff("BRANCH_MANAGER", newBranch()));
        assertThat(reject(requestId, otherManager, input).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reject(Long.MAX_VALUE, managerToken, input).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertPending();
    }

    private void assertPending() {
        assertThat(jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, requestId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'return_requests' "
                + "AND entity_id = ?", Long.class, requestId)).isZero();
    }

    private ResponseEntity<Map<String, Object>> reject(long id, String jwt, Object input) {
        return call(HttpMethod.POST, PATH + "/" + id + "/reject", jwt, input);
    }
}
