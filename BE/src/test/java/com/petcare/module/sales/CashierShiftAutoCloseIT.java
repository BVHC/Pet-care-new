package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.job.InAppNotificationJob;
import com.petcare.module.sales.job.CashierShiftAutoCloseJob;
import com.petcare.support.MutableClock;

/**
 * ST13 tự chốt ca thu ngân (Ca thu ngân#4, #5; BR-TG-05; docs/adr/0064) trên Postgres 17 thật, đồng hồ dịch được (năm
 * 2005): ca thường chốt sau giờ đóng cửa + grace, ca ngoài giờ chốt khi tới giờ mở cửa kế tiếp, ngày nghỉ khai báo sau
 * khi mở ca, thông báo tới quản lý (không có thì SUPER_MANAGER), ca đang có phiếu thu bị bỏ qua, lỗi thông báo
 * rollback cả việc chốt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class CashierShiftAutoCloseIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");
    /** 10:00 giờ Việt Nam. */
    private static final Instant OPENED = Instant.parse("2005-06-15T03:00:00Z");
    private static final String TEMPLATE = "CASHIER_SHIFT_AUTO_CLOSED_APP";

    @Autowired
    private MutableClock clock;
    @Autowired
    private CashierShiftAutoCloseJob job;
    @Autowired
    private InAppNotificationJob inAppJob;

    private long branch;
    private long receptionist;
    private long manager;
    private String tManager;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        branch = newBranch();
        openingHours(branch, LocalDate.parse("2005-01-01"), "08:00", "18:00", null, null);
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        tManager = token(manager);
    }

    private void runAt(String utc) {
        clock.set(Instant.parse(utc));
        job.run();
    }

    private String status(long shiftId) {
        return (String) shiftRow(shiftId).get("status");
    }

    private long notices(long accountId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = ? AND recipient_account_id = ?""",
                Long.class, TEMPLATE, accountId);
    }

    @Test
    void regularShiftClosesAfterGraceAndTheManagerIsNotified() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        long customer = newCustomer();
        paymentSql(branch, customer, shift, "CASH", 100_000, receptionist);
        paymentSql(branch, customer, shift, "TRANSFER", 40_000, receptionist);

        runAt("2005-06-15T11:29:00Z"); // 18:29: chưa tới hạn (18:00 + 30 phút)
        assertThat(status(shift)).isEqualTo("OPEN");
        assertThat(notices(manager)).isZero();

        runAt("2005-06-15T11:30:00Z"); // 18:30: đúng hạn
        Map<String, Object> row = shiftRow(shift);
        assertThat(row).containsEntry("status", "CLOSED").containsEntry("auto_closed", true)
                .containsEntry("counted_cash", null).containsEntry("expected_cash", 100_000L)
                .containsEntry("expected_transfer", 40_000L).containsEntry("difference", null);
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(notices(manager)).isEqualTo(1L);
        Map<String, Object> payload = jdbc.queryForMap("""
                SELECT payload::text AS payload, channel, status FROM notification_outbox
                WHERE template_code = ? AND recipient_account_id = ?""", TEMPLATE, manager);
        assertThat(payload).containsEntry("channel", "IN_APP");
        assertThat((String) payload.get("payload")).contains("\"loai_ca\": \"thường\"")
                .contains("\"ngay_ca\": \"15/06/2005\"").contains("\"gio_chot\": \"18:30 15/06/2005\"");
    }

    @Test
    void theNoticeIsDeliveredIntoTheManagersInbox() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        runAt("2005-06-15T12:00:00Z");
        assertThat(status(shift)).isEqualTo("CLOSED");

        inAppJob.run();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE account_id = ? AND type = 'CASHIER_SHIFT_AUTO_CLOSED'""",
                Long.class, manager)).isEqualTo(1L);
    }

    /** Review Focus 4: ca ngoài giờ chốt khi tới giờ mở cửa kế tiếp, vẫn thuộc ngày mở ca. */
    @Test
    void afterHoursShiftClosesAtTheNextOpeningStartOfTheNextMorning() {
        acceptAfterHours(branch);
        long shift = openShiftSql(branch, receptionist, true, DAY, Instant.parse("2005-06-15T15:00:00Z")); // 22:00

        runAt("2005-06-16T00:59:59Z"); // 07:59:59
        assertThat(status(shift)).isEqualTo("OPEN");
        runAt("2005-06-16T01:00:00Z"); // 08:00

        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED").containsEntry("auto_closed", true)
                .containsEntry("business_date", java.sql.Date.valueOf(DAY));
    }

    /** Review Focus 4: ngày nghỉ khai báo sau khi đã mở ca thường → chốt lúc 00:00 ngày kế tiếp. */
    @Test
    void holidayDeclaredAfterOpeningClosesAtMidnightOfTheNextDay() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        holiday(branch, DAY, manager);

        runAt("2005-06-15T16:59:59Z"); // 23:59:59
        assertThat(status(shift)).isEqualTo("OPEN");
        runAt("2005-06-15T17:00:00Z"); // 00:00 ngày 16

        assertThat(status(shift)).isEqualTo("CLOSED");
    }

    @Test
    void rerunningDoesNothingMore() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        runAt("2005-06-15T12:00:00Z");
        Object closedAt = shiftRow(shift).get("closed_at");

        runAt("2005-06-15T13:00:00Z");

        assertThat(shiftRow(shift).get("closed_at")).isEqualTo(closedAt);
        assertThat(notices(manager)).isEqualTo(1L);
    }

    @Test
    void autoClosedShiftNeedsACountToBeReconciled() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        runAt("2005-06-15T12:00:00Z");
        tManager = token(manager);

        ResponseEntity<Map<String, Object>> without = call(HttpMethod.POST,
                "/api/cashier-shifts/" + shift + "/reconcile", tManager, Map.of("note", "ghi chú"));
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) without.getBody().get("message")).endsWith("(BR-TG-05)");

        Map<String, Object> done = data(call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/reconcile",
                tManager, Map.of("countedCash", 0, "note", "Quản lý đếm")), HttpStatus.OK);
        assertThat(done).containsEntry("status", "RECONCILED").containsEntry("autoClosed", true);
    }

    @Test
    void branchWithoutAManagerNotifiesTheSuperManagers() {
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", manager);
        long superManager = staff("SUPER_MANAGER", null);
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);

        runAt("2005-06-15T12:00:00Z");

        assertThat(status(shift)).isEqualTo("CLOSED");
        assertThat(notices(superManager)).isEqualTo(1L);
        assertThat(notices(manager)).isZero();
    }

    /** Review Focus 2: ca đang có phiếu thu giữ FOR SHARE thì ST13 bỏ qua, lượt sau mới chốt. */
    @Test
    void autoCloseSkipsAShiftHeldByAPayment() throws Exception {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM cashier_shifts WHERE id = ? FOR SHARE", Long.class, shift);
                    locked.complete(null);
                    release.join();
                }));
        try {
            locked.get(5, TimeUnit.SECONDS);
            runAt("2005-06-15T12:00:00Z");
            assertThat(status(shift)).as("đang bị khóa: bỏ qua").isEqualTo("OPEN");
        } finally {
            release.complete(null);
            holder.get(5, TimeUnit.SECONDS);
        }

        job.run();

        assertThat(status(shift)).isEqualTo("CLOSED");
    }

    @Test
    void notificationFailureRollsBackTheCloseAndTheJobDoesNotThrow() {
        long shift = openShiftSql(branch, receptionist, false, DAY, OPENED);
        clock.set(Instant.parse("2005-06-15T12:00:00Z"));

        whileInsertsFail("notification_outbox", "NEW.template_code = '" + TEMPLATE + "'", job::run);

        assertThat(status(shift)).isEqualTo("OPEN");
        job.run();
        assertThat(status(shift)).isEqualTo("CLOSED");
    }
}
