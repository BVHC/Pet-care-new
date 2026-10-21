package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.sales.job.PendingOrderDigestJob;
import com.petcare.support.MutableClock;

/**
 * ST13 báo Order PENDING quá hạn cho quản lý cuối ngày (BR-BH-05; docs/adr/0064) trên Postgres 17 thật, đồng hồ dịch
 * được (năm 2005): đếm theo chi nhánh, cùng định nghĩa với bộ lọc {@code pendingOverdue} của danh sách Order (#2),
 * không báo khi không có Order quá hạn, người nhận là BRANCH_MANAGER.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class PendingOrderDigestIT extends CashierItSupport {

    private static final String TEMPLATE = "PENDING_ORDERS_DIGEST_APP";
    /** Trước 00:00 ngày 15/6/2005 giờ Việt Nam → quá hạn khi N = 1. */
    private static final Instant OVERDUE = Instant.parse("2005-06-14T10:00:00Z");
    private static final Instant NOT_OVERDUE = Instant.parse("2005-06-15T01:00:00Z");

    @Autowired
    private MutableClock clock;
    @Autowired
    private PendingOrderDigestJob job;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long otherManager;
    private long customer;
    private long goods;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        otherBranch = newBranch();
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        otherManager = staff("BRANCH_MANAGER", otherBranch);
        customer = newCustomer();
        goods = newPricedProduct("GOODS", false, 100_000);
    }

    private long pendingSince(long branchId, Instant pendingAt) {
        long id = pendingOrderSql(branchId, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000));
        jdbc.update("UPDATE orders SET pending_at = ? WHERE id = ?", java.sql.Timestamp.from(pendingAt), id);
        return id;
    }

    private long notices(long accountId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = ? AND recipient_account_id = ?""",
                Long.class, TEMPLATE, accountId);
    }

    @Test
    void overdueOrdersAreCountedPerBranchAndTheManagerIsNotified() {
        pendingSince(branch, OVERDUE);
        pendingSince(branch, OVERDUE.minusSeconds(86_400));
        pendingSince(branch, NOT_OVERDUE);
        pendingSince(otherBranch, OVERDUE);

        job.run();

        assertThat(notices(manager)).isEqualTo(1L);
        assertThat(notices(otherManager)).isEqualTo(1L);
        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM notification_outbox WHERE template_code = ? AND recipient_account_id = ?""",
                String.class, TEMPLATE, manager);
        assertThat(payload).contains("\"so_order\": \"2\"").contains("\"so_ngay\": \"1\"")
                .contains("\"ngay_cu_nhat\": \"13/06/2005\"");
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM notification_outbox WHERE template_code = ? AND recipient_account_id = ?""",
                String.class, TEMPLATE, otherManager)).contains("\"so_order\": \"1\"");
    }

    @Test
    void theCountMatchesTheOverdueFilterOfTheOrderList() {
        pendingSince(branch, OVERDUE);
        pendingSince(branch, OVERDUE);
        pendingSince(branch, NOT_OVERDUE);
        String tManager = token(manager);

        job.run();

        Map<String, Object> page = data(call(HttpMethod.GET, "/api/orders?pendingOverdue=true", tManager, null),
                HttpStatus.OK);
        assertThat(page.get("totalElements")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'so_order' FROM notification_outbox
                WHERE template_code = ? AND recipient_account_id = ?""", String.class, TEMPLATE, manager))
                .isEqualTo("2");
    }

    @Test
    void ordersThatAreNotPendingAreNotCounted() {
        long paid = pendingSince(branch, OVERDUE);
        jdbc.update("UPDATE orders SET status = 'CANCELLED', cancel_type = 'UNPAID', cancelled_at = now(), "
                + "cancelled_by = ?, cancel_reason = 'x' WHERE id = ?", manager, paid);

        job.run();

        assertThat(notices(manager)).isZero();
    }

    @Test
    void branchWithoutOverdueOrdersGetsNothing() {
        pendingSince(branch, NOT_OVERDUE);

        job.run();

        assertThat(notices(manager)).isZero();
    }

    @Test
    void thresholdFollowsTheConfiguredDaysAtRunTime() {
        pendingSince(branch, Instant.parse("2005-06-14T10:00:00Z")); // hôm qua
        pendingSince(branch, Instant.parse("2005-06-12T10:00:00Z")); // 3 ngày trước
        jdbc.update("UPDATE system_configs SET value = '3' WHERE key = 'order.pending_alert_days'");
        try {
            reloadConfigs();

            job.run();

            assertThat(jdbc.queryForObject("""
                    SELECT payload->>'so_order' FROM notification_outbox
                    WHERE template_code = ? AND recipient_account_id = ?""", String.class, TEMPLATE, manager))
                    .isEqualTo("1");
        } finally {
            jdbc.update("UPDATE system_configs SET value = '1' WHERE key = 'order.pending_alert_days'");
            reloadConfigs();
        }
    }

    @Autowired
    private com.petcare.module.identity.service.SystemConfigService configService;

    private void reloadConfigs() {
        configService.reload();
    }
}
