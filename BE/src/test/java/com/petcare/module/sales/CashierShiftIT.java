package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.petcare.support.MutableClock;

/**
 * Ca thu ngân (sales-v1 #16–#21; UC71, UC72; Ca thu ngân#1–#6; BR-TG-01, 05, BR-CN-05; docs/adr/0064) trên Postgres 17
 * thật qua HTTP, đồng hồ dịch được (năm 2005): hình dạng hợp đồng, luật giờ mở cửa, race mở ca, ma trận vai trò.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class CashierShiftIT extends CashierItSupport {

    private static final LocalDate DAY = LocalDate.parse("2005-06-15");

    @Autowired
    private MutableClock clock;

    private long branch;
    private long otherBranch;
    private long receptionist;
    private long manager;
    private long vet;
    private String tReceptionist;
    private String tManager;
    private String tVet;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START); // 2005-06-15 10:00 giờ Việt Nam
        branch = newBranch();
        otherBranch = newBranch();
        openingHours(branch, LocalDate.parse("2005-01-01"), "08:00", "18:00", null, null);
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        vet = staff("VET", branch);
        tReceptionist = token(receptionist);
        tManager = token(manager);
        tVet = token(vet);
    }

    private ResponseEntity<Map<String, Object>> open(String token, boolean afterHours) {
        return call(HttpMethod.POST, "/api/cashier-shifts", token, Map.of("afterHours", afterHours));
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, HttpStatus status, String rule) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat((String) response.getBody().get("message")).endsWith("(" + rule + ")");
    }

    // ---------------------------------------------------------------- #16 mở ca

    @Test
    void opensRegularShiftAndResponseMatchesTheRow() {
        Map<String, Object> shift = data(open(tReceptionist, false), HttpStatus.CREATED);

        assertThat(shift).containsOnlyKeys("shiftId", "branchId", "cashierId", "cashierName", "isAfterHours",
                "businessDate", "status", "openedAt", "closedAt", "autoClosed", "expectedCash", "expectedTransfer",
                "countedCash", "difference", "reconcileNote", "reconciledBy", "reconciledAt", "paymentCount");
        long id = ((Number) shift.get("shiftId")).longValue();
        Map<String, Object> row = shiftRow(id);
        assertThat(shift).containsEntry("branchId", (int) branch).containsEntry("cashierId", (int) receptionist)
                .containsEntry("isAfterHours", false).containsEntry("status", "OPEN")
                .containsEntry("autoClosed", false).containsEntry("paymentCount", 0)
                .containsEntry("businessDate", "2005-06-15").containsEntry("closedAt", null)
                .containsEntry("countedCash", null);
        assertThat(shift.get("cashierName")).isEqualTo("Kho IT RECEPTIONIST");
        assertThat(Instant.parse((String) shift.get("openedAt")))
                .isEqualTo(((java.sql.Timestamp) row.get("opened_at")).toInstant());
        assertThat(row).containsEntry("status", "OPEN").containsEntry("is_after_hours", false)
                .containsEntry("auto_closed", false);
    }

    @Test
    void getMineReflectsTheOpenShift() {
        Map<String, Object> before = data(call(HttpMethod.GET, "/api/me/cashier-shift", tReceptionist, null),
                HttpStatus.OK);
        assertThat(before).containsOnlyKeys("open", "shift").containsEntry("open", false)
                .containsEntry("shift", null);

        data(open(tReceptionist, false), HttpStatus.CREATED);
        Map<String, Object> after = data(call(HttpMethod.GET, "/api/me/cashier-shift", tReceptionist, null),
                HttpStatus.OK);

        assertThat(after).containsEntry("open", true);
        assertThat(map(after.get("shift"))).containsEntry("status", "OPEN");
    }

    @Test
    void outsideOpeningHoursRegularShiftIs400BrTg05() {
        clock.set(Instant.parse("2005-06-15T14:00:00Z")); // 21:00
        tReceptionist = token(receptionist);

        assertRule(open(tReceptionist, false), HttpStatus.BAD_REQUEST, "BR-TG-05");
    }

    @Test
    void holidayRegularShiftIs400BrTg05() {
        holiday(branch, DAY, manager);

        assertRule(open(tReceptionist, false), HttpStatus.BAD_REQUEST, "BR-TG-05");
    }

    @Test
    void lunchBreakRegularShiftIs400BrTg05() {
        jdbc.update("DELETE FROM opening_hours WHERE branch_id = ?", branch);
        openingHours(branch, LocalDate.parse("2005-01-01"), "08:00", "12:00", "13:30", "18:00");
        clock.set(Instant.parse("2005-06-15T05:30:00Z")); // 12:30
        tReceptionist = token(receptionist);

        assertRule(open(tReceptionist, false), HttpStatus.BAD_REQUEST, "BR-TG-05");
    }

    @Test
    void afterHoursShiftOnAFlaggedBranchAtNightOpens() {
        acceptAfterHours(branch);
        clock.set(Instant.parse("2005-06-15T15:00:00Z")); // 22:00
        tReceptionist = token(receptionist);

        Map<String, Object> shift = data(open(tReceptionist, true), HttpStatus.CREATED);

        assertThat(shift).containsEntry("isAfterHours", true).containsEntry("businessDate", "2005-06-15");
    }

    @Test
    void afterHoursShiftAfterMidnightKeepsTheOpeningDate() {
        acceptAfterHours(branch);
        clock.set(Instant.parse("2005-06-15T17:30:00Z")); // 00:30 ngày 16/6
        tReceptionist = token(receptionist);

        Map<String, Object> shift = data(open(tReceptionist, true), HttpStatus.CREATED);

        assertThat(shift).containsEntry("businessDate", "2005-06-16");
    }

    @Test
    void afterHoursShiftWithoutTheFlagIs400BrCn05() {
        clock.set(Instant.parse("2005-06-15T15:00:00Z"));
        tReceptionist = token(receptionist);

        assertRule(open(tReceptionist, true), HttpStatus.BAD_REQUEST, "BR-CN-05");
    }

    @Test
    void afterHoursShiftInsideOpeningHoursIs400BrTg05() {
        acceptAfterHours(branch);

        assertRule(open(tReceptionist, true), HttpStatus.BAD_REQUEST, "BR-TG-05");
    }

    @Test
    void secondOpenShiftIs409() {
        data(open(tReceptionist, false), HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> second = open(tReceptionist, false);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void missingOrNullAfterHoursIs400() {
        assertThat(call(HttpMethod.POST, "/api/cashier-shifts", tReceptionist, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(callRaw(HttpMethod.POST, "/api/cashier-shifts", tReceptionist, "{\"afterHours\":\"x\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void concurrentOpenTwiceGivesExactlyOneShift() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<Map<String, Object>>> task = () -> open(tReceptionist, false);
            List<Future<ResponseEntity<Map<String, Object>>>> results = pool.invokeAll(List.of(task, task));
            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(results.get(0).get(20, TimeUnit.SECONDS).getStatusCode().value()),
                    HttpStatus.valueOf(results.get(1).get(20, TimeUnit.SECONDS).getStatusCode().value()));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cashier_shifts WHERE cashier_id = ? AND status = 'OPEN'",
                Long.class, receptionist)).isEqualTo(1L);
    }

    @Test
    void otherRolesAre403() {
        assertThat(open(tVet, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(open(tManager, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/me/cashier-shift", tManager, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(open(null, false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openShiftBlocksDisablingTheReceptionist() {
        data(open(tReceptionist, false), HttpStatus.CREATED);
        long superManager = staff("SUPER_MANAGER", null);

        ResponseEntity<Map<String, Object>> disable = call(HttpMethod.POST,
                "/api/staff/" + receptionist + "/disable", token(superManager), null);

        assertRule(disable, HttpStatus.BAD_REQUEST, "BR-QT-08");
    }

    /** Nghĩa vụ ADR-0031 mục 9: mở ca khóa accounts FOR SHARE, nên chờ điều chuyển đang chạy rồi thấy chi nhánh mới. */
    @Test
    void openWaitsForAConcurrentTransferAndOpensAtTheNewBranch() throws Exception {
        openingHours(otherBranch, LocalDate.parse("2005-01-01"), "08:00", "18:00", null, null);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> transfer = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class,
                            receptionist);
                    locked.complete(null);
                    release.join();
                    jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch,
                            receptionist);
                }));
        try {
            locked.get(5, TimeUnit.SECONDS);
            CompletableFuture<ResponseEntity<Map<String, Object>>> request = CompletableFuture
                    .supplyAsync(() -> open(tReceptionist, false));
            awaitBlockedOn("for share");
            release.complete(null);
            transfer.get(5, TimeUnit.SECONDS);

            Map<String, Object> shift = data(request.get(20, TimeUnit.SECONDS), HttpStatus.CREATED);
            assertThat(shift).containsEntry("branchId", (int) otherBranch);
        } finally {
            release.complete(null);
        }
    }

    // ---------------------------------------------------------------- #18 chốt ca

    private ResponseEntity<Map<String, Object>> close(String token, long shiftId, Object countedCash) {
        return call(HttpMethod.POST, "/api/cashier-shifts/" + shiftId + "/close", token,
                countedCash == null ? Map.of() : Map.of("countedCash", countedCash));
    }

    private long openedShift() {
        return ((Number) data(open(tReceptionist, false), HttpStatus.CREATED).get("shiftId")).longValue();
    }

    @Test
    void closeStoresCountedCashAndTotalsByMethod() {
        long shift = openedShift();
        long customer = newCustomer();
        paymentSql(branch, customer, shift, "CASH", 100_000, receptionist);
        paymentSql(branch, customer, shift, "CASH", 50_000, receptionist);
        paymentSql(branch, customer, shift, "TRANSFER", 30_000, receptionist);

        Map<String, Object> closed = data(close(tReceptionist, shift, 140_000), HttpStatus.OK);

        assertThat(closed).containsEntry("status", "CLOSED").containsEntry("expectedCash", 150_000)
                .containsEntry("expectedTransfer", 30_000).containsEntry("countedCash", 140_000)
                .containsEntry("difference", null).containsEntry("autoClosed", false)
                .containsEntry("paymentCount", 3);
        assertThat(closed.get("closedAt")).isNotNull();
        Map<String, Object> row = shiftRow(shift);
        assertThat(row).containsEntry("status", "CLOSED").containsEntry("expected_cash", 150_000L)
                .containsEntry("expected_transfer", 30_000L).containsEntry("counted_cash", 140_000L);
    }

    @Test
    void closeWithoutPaymentsStoresZeroTotals() {
        long shift = openedShift();

        Map<String, Object> closed = data(close(tReceptionist, shift, 0), HttpStatus.OK);

        assertThat(closed).containsEntry("expectedCash", 0).containsEntry("expectedTransfer", 0)
                .containsEntry("countedCash", 0);
    }

    @Test
    void closingTwiceIs409() {
        long shift = openedShift();
        data(close(tReceptionist, shift, 0), HttpStatus.OK);

        assertThat(close(tReceptionist, shift, 0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void closeNeedsANonNegativeCount() {
        long shift = openedShift();

        assertThat(close(tReceptionist, shift, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(close(tReceptionist, shift, -5).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(shiftRow(shift)).containsEntry("status", "OPEN");
    }

    @Test
    void closingAnotherCashiersShiftOrAMissingOneIsRefused() {
        long shift = openedShift();
        String otherReceptionist = token(receptionist(branch));
        String outsider = token(receptionist(otherBranch));

        assertThat(close(otherReceptionist, shift, 0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(close(outsider, shift, 0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(close(tManager, shift, 0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(close(tReceptionist, Long.MAX_VALUE, 0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(shiftRow(shift)).containsEntry("status", "OPEN");
    }

    @Test
    void closedShiftLetsTheCashierOpenAnother() {
        long first = openedShift();
        data(close(tReceptionist, first, 0), HttpStatus.OK);

        long second = openedShift();

        assertThat(second).isNotEqualTo(first);
    }

    private void awaitBlockedOn(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%' AND query ILIKE ?""",
                    Long.class, "%" + fragment + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("không có câu nào chờ khóa: " + fragment);
    }
}
