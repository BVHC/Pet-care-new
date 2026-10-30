package com.petcare.module.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.CashierClockConfig;
import com.petcare.module.sales.CashierItSupport;
import com.petcare.support.MutableClock;

/**
 * UC89 (report-v1 #1–7; BR-BC-01…04, docs/adr/0087) qua HTTP trên Postgres 17 thật: phạm vi chi nhánh (SUPER_MANAGER
 * toàn chuỗi hoặc lọc, BRANCH_MANAGER chỉ chi nhánh mình), kỳ tối đa 12 tháng, vai trò khác bị từ chối, và mỗi báo cáo trả
 * số liệu đã gắn tên. Order chèn bằng SQL (luồng thu tiền đã có IT riêng); kỳ tháng 6/2005 theo {@link CashierClockConfig}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class ReportControllerIT extends CashierItSupport {

    private static final String PERIOD = "from=2005-06-01&to=2005-06-30";
    private static final Instant IN_PERIOD = Instant.parse("2005-06-15T05:00:00Z");

    @Autowired
    private MutableClock clock;

    private long b1;
    private long b2;
    private long cashier1;
    private long cashier2;
    private String superManager;
    private String manager1;
    private long customerForFeedback;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        b1 = newBranch();
        b2 = newBranch();
        cashier1 = receptionist(b1);
        cashier2 = receptionist(b2);
        superManager = token(staff("SUPER_MANAGER", null));
        manager1 = token(staff("BRANCH_MANAGER", b1));
    }

    @AfterEach
    void cleanReport() {
        jdbc.update("DELETE FROM feedbacks WHERE content LIKE 'Báo cáo IT %'");
    }

    private long paidOrder(long branch, long cashier, String source, long amount) {
        long customer = newCustomer();
        long shift = openShiftSql(branch, cashier, false, LocalDate.parse("2005-06-15"), IN_PERIOD);
        long payment = paymentSql(branch, customer, shift, "CASH", amount, cashier);
        jdbc.update("UPDATE payments SET paid_at = ? WHERE id = ?", Timestamp.from(IN_PERIOD), payment);
        Long visit = source.equals("VISIT") ? newVisit(branch, customer, newPet(customer), "MEDICAL", "WAITING",
                null, cashier) : null;
        return jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, total_amount, created_by,
                                    pending_at, paid_at, payment_id)
                VALUES (?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "DH-IT-" + UUID.randomUUID().toString().substring(0, 12), source, customer, branch,
                visit, amount, cashier, Timestamp.from(IN_PERIOD), Timestamp.from(IN_PERIOD), payment);
    }

    private Map<String, Object> get(String path, String token) {
        return data(call(HttpMethod.GET, path, token, null), HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("rows");
    }

    @Test
    void superManagerSeesChainAndCanFilterBranch() {
        paidOrder(b1, cashier1, "RETAIL", 30_000);
        paidOrder(b2, cashier2, "RETAIL", 70_000);

        Map<String, Object> chain = get("/api/reports/revenue?" + PERIOD, superManager);
        assertThat(rows(chain)).extracting(r -> number(r.get("branchId"))).contains(b1, b2);
        assertThat(number(chain.get("totalAmount"))).isGreaterThanOrEqualTo(100_000L);

        Map<String, Object> only2 = get("/api/reports/revenue?branchId=" + b2 + "&" + PERIOD, superManager);
        assertThat(rows(only2)).singleElement().satisfies(r -> {
            assertThat(number(r.get("branchId"))).isEqualTo(b2);
            assertThat((String) r.get("branchName")).startsWith("KhoIT chi nhánh");
            assertThat(r.get("source")).isEqualTo("RETAIL");
            assertThat(number(r.get("orderCount"))).isEqualTo(1L);
            assertThat(number(r.get("amount"))).isEqualTo(70_000L);
        });
        assertThat(number(only2.get("totalAmount"))).isEqualTo(70_000L);
        assertThat(only2).containsEntry("from", "2005-06-01").containsEntry("to", "2005-06-30");
    }

    @Test
    void branchManagerWithoutBranchIdSeesOnlyOwnBranch() {
        paidOrder(b1, cashier1, "RETAIL", 30_000);
        paidOrder(b2, cashier2, "RETAIL", 70_000);

        Map<String, Object> report = get("/api/reports/revenue?" + PERIOD, manager1);

        assertThat(rows(report)).isNotEmpty().allSatisfy(r -> assertThat(number(r.get("branchId"))).isEqualTo(b1));
        assertThat(number(report.get("totalAmount"))).isEqualTo(30_000L);
    }

    @Test
    void branchManagerOtherBranchIs403() {
        assertError(call(HttpMethod.GET, "/api/reports/revenue?branchId=" + b2 + "&" + PERIOD, manager1, null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void scopeIsCheckedBeforeThePeriod() {
        assertError(call(HttpMethod.GET, "/api/reports/revenue?branchId=" + b2 + "&from=2005-06-30&to=2005-06-01",
                manager1, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void periodOverTheLimitIs400WithTheRule() {
        assertRule(call(HttpMethod.GET, "/api/reports/revenue?from=2005-01-01&to=2006-01-01", superManager, null),
                "BR-BC-01");
        assertThat(call(HttpMethod.GET, "/api/reports/revenue?from=2005-01-01&to=2005-12-31", superManager, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fromAfterToIs400WithTheRule() {
        assertRule(call(HttpMethod.GET, "/api/reports/lost-revenue?from=2005-06-30&to=2005-06-01", superManager,
                null), "BR-BC-01");
    }

    @Test
    void missingFromIs400NotServerError() {
        assertThat(call(HttpMethod.GET, "/api/reports/revenue?to=2005-06-30", superManager, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rolesOtherThanManagersAreDeniedAndAnonymousIs401() {
        String receptionist = token(cashier1);
        String admin = token(staff("ADMIN", null));

        assertError(call(HttpMethod.GET, "/api/reports/revenue?" + PERIOD, receptionist, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, "/api/reports/stock-alerts", admin, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertThat(call(HttpMethod.GET, "/api/reports/revenue?" + PERIOD, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void lostRevenueAndCompletedServicesAreNamed() {
        long service = newService("MEDICAL", 100_000);
        long customer = newCustomer();
        long visit = newVisit(b1, customer, newPet(customer), "MEDICAL", "WAITING", null, cashier1);
        long order = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, total_amount, created_by,
                                    pending_at, cancelled_at, cancel_type, cancel_reason)
                VALUES (?, 'VISIT', ?, ?, ?, 'CANCELLED', 100000, ?, ?, ?, 'UNPAID', 'Khách bỏ về') RETURNING id
                """, Long.class, "DH-IT-" + UUID.randomUUID().toString().substring(0, 12), customer, b1, visit,
                cashier1, Timestamp.from(IN_PERIOD), Timestamp.from(IN_PERIOD));
        jdbc.update("""
                INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price, line_total,
                                         added_by, owner_account_id)
                VALUES (?, 'SERVICE', ?, 'Dịch vụ IT', 1, 100000, 100000, ?, ?)""", order, service, cashier1, cashier1);

        Map<String, Object> lost = get("/api/reports/lost-revenue?branchId=" + b1 + "&" + PERIOD, superManager);
        assertThat(number(lost.get("count"))).isEqualTo(1L);
        assertThat(number(lost.get("totalAmount"))).isEqualTo(100_000L);
        assertThat(rows(lost)).singleElement().satisfies(r -> {
            assertThat(r.get("reason")).isEqualTo("Khách bỏ về");
            assertThat(r.get("source")).isEqualTo("VISIT");
            assertThat((String) r.get("orderCode")).startsWith("DH-IT-");
        });

        Map<String, Object> services = get("/api/reports/completed-services?branchId=" + b1 + "&" + PERIOD,
                superManager);
        assertThat(rows(services)).singleElement().satisfies(r -> {
            assertThat(number(r.get("serviceId"))).isEqualTo(service);
            assertThat((String) r.get("serviceName")).startsWith("Dịch vụ sales IT");
            assertThat(r.get("serviceGroup")).isEqualTo("MEDICAL");
            assertThat(number(r.get("count"))).isEqualTo(1L);
        });
    }

    @Test
    void stockAlertsAreNamedAndMarkExpired() {
        long actor = staff("BRANCH_MANAGER", b1);
        long low = newProduct("GOODS", false);
        jdbc.update("UPDATE inventory_items SET min_quantity = 10 WHERE id = ?", inventoryItem(b1, low));
        defaultLot(b1, low, 2, actor);
        long expiring = newProduct("GOODS", true);
        LocalDate today = LocalDate.now(clock);
        lot(b1, expiring, "HH", today.minusDays(1).toString(), 4, actor);
        lot(b1, expiring, "SAP", today.plusDays(10).toString(), 5, actor);

        Map<String, Object> report = get("/api/reports/stock-alerts?branchId=" + b1, manager1);

        assertThat(list(report, "lowStock")).singleElement().satisfies(r -> {
            assertThat(number(r.get("productId"))).isEqualTo(low);
            assertThat((String) r.get("productName")).startsWith("Sản phẩm kho");
            assertThat(number(r.get("availableQuantity"))).isEqualTo(2L);
            assertThat(number(r.get("minQuantity"))).isEqualTo(10L);
        });
        assertThat(list(report, "expiringLots")).extracting(r -> r.get("lotNumber"), r -> r.get("expired"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HH", true),
                        org.assertj.core.groups.Tuple.tuple("SAP", false));
        assertThat(get("/api/reports/stock-alerts?branchId=" + b1 + "&withinDays=5", manager1).get("expiringLots"))
                .asList().hasSize(1);
        assertThat(call(HttpMethod.GET, "/api/reports/stock-alerts?withinDays=366", superManager, null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> report, String key) {
        return (List<Map<String, Object>>) report.get(key);
    }

    @Test
    void feedbackIsNamedAndNoShowAndRevaccinationAnswer() {
        customerForFeedback = newCustomer();
        jdbc.update("""
                INSERT INTO feedbacks (customer_id, topic, branch_id, rating, content, status, created_at, updated_at)
                VALUES (?, 'BRANCH_SERVICE', ?, 5, 'Báo cáo IT feedback đủ dài.', 'NEW', ?, ?)""", customerForFeedback,
                b1, Timestamp.from(IN_PERIOD), Timestamp.from(IN_PERIOD));

        Map<String, Object> feedback = get("/api/reports/feedback?branchId=" + b1 + "&" + PERIOD, manager1);
        assertThat(rows(feedback)).singleElement().satisfies(r -> {
            assertThat(number(r.get("branchId"))).isEqualTo(b1);
            assertThat((String) r.get("branchName")).startsWith("KhoIT chi nhánh");
            assertThat(number(r.get("count"))).isEqualTo(1L);
            assertThat(((Number) r.get("averageRating")).doubleValue()).isEqualTo(5.0);
        });

        assertThat(rows(get("/api/reports/no-show?branchId=" + b1 + "&" + PERIOD, manager1))).isEmpty();
        assertThat(rows(get("/api/reports/revaccination-return?branchId=" + b1 + "&" + PERIOD, manager1))).isEmpty();
    }
}
