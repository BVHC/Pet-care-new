package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.petcare.module.sales.job.CashierShiftAutoCloseJob;
import com.petcare.support.MutableClock;

/**
 * T45 — đủ 6 chuyển của bảng "8. Ca thu ngân" (docs/03-state-machines.md) qua endpoint / job thật, <b>không stand-in
 * nào</b>, cùng cặp sai có đường HTTP (chốt / đối soát từ trạng thái không cho phép) → 409 và không ghi gì. Ca mở bằng
 * {@code POST /api/cashier-shifts}; ST13 là bean {@link CashierShiftAutoCloseJob} thật. Era <b>năm 1986</b>: ST13 quét
 * mọi ca {@code OPEN} nên IT này phải cũ hơn mọi era khác (CLAUDE.md, mục Testing); chi nhánh mở 08:00–18:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierShiftFsmIT.TestBeans.class})
class CashierShiftFsmIT extends CashierItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(1986, 3, 12);
    private static final String PATH = "/api/cashier-shifts";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(DAY, 10, 0));
        }
    }

    @Autowired
    private MutableClock clock;
    @Autowired
    private CashierShiftAutoCloseJob job;

    private long branch;
    private long receptionist;
    private long manager;
    private String receptionistToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        clock.set(at(DAY, 10, 0));
        branch = newBranch();
        openingHours(branch, LocalDate.of(1986, 1, 1), "08:00", "18:00", null, null);
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        receptionistToken = token(receptionist);
        managerToken = token(manager);
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — mở ca thường trong giờ mở cửa: {@code OPEN}, {@code is_after_hours = false}. */
    @Test
    void openingDuringHoursStartsARegularShift() {
        long shift = open(false);

        assertThat(shiftRow(shift)).containsEntry("status", "OPEN").containsEntry("is_after_hours", false)
                .containsEntry("auto_closed", false);
    }

    /** #2 — mở ca ngoài giờ ở chi nhánh nhận cấp cứu: {@code is_after_hours = true}. */
    @Test
    void openingAfterHoursAtAnEmergencyBranchStartsAnAfterHoursShift() {
        acceptAfterHours(branch);
        clock.set(at(DAY, 22, 0));
        refreshTokens();

        long shift = open(true);

        assertThat(shiftRow(shift)).containsEntry("status", "OPEN").containsEntry("is_after_hours", true);
    }

    /** #3 — chốt ca với số thực đếm: {@code CLOSED}, tổng theo phương thức, không phải tự chốt. */
    @Test
    void closingWithCountedCashClosesTheShift() {
        long shift = open(false);
        paymentSql(branch, newCustomer(), shift, "CASH", 100_000, receptionist);

        data(close(shift, 90_000), HttpStatus.OK);

        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED").containsEntry("auto_closed", false)
                .containsEntry("counted_cash", 90_000L).containsEntry("expected_cash", 100_000L)
                .containsEntry("difference", null); // chênh lệch chỉ tính khi đối soát (Ca thu ngân#6)
        assertThat(shiftRow(shift).get("closed_at")).isNotNull();
    }

    /** #4 — ST13 tự chốt ca thường sau giờ đóng cửa + 30 phút [CFG]; báo BRANCH_MANAGER. */
    @Test
    void st13AutoClosesARegularShiftThirtyMinutesAfterClosingTime() {
        long shift = open(false);

        runAt(at(DAY, 18, 29));
        assertThat(status(shift)).isEqualTo("OPEN");

        runAt(at(DAY, 18, 30));
        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED").containsEntry("auto_closed", true)
                .containsEntry("counted_cash", null);
        assertThat(notices(manager)).isOne();
    }

    /** #5 — ST13 tự chốt ca ngoài giờ khi tới giờ mở cửa kế tiếp, vẫn thuộc ngày mở ca. */
    @Test
    void st13AutoClosesAnAfterHoursShiftWhenTheBranchOpensAgain() {
        acceptAfterHours(branch);
        clock.set(at(DAY, 22, 0));
        refreshTokens();
        long shift = open(true);

        runAt(at(DAY.plusDays(1), 7, 59));
        assertThat(status(shift)).isEqualTo("OPEN");

        runAt(at(DAY.plusDays(1), 8, 0));
        assertThat(shiftRow(shift)).containsEntry("status", "CLOSED").containsEntry("auto_closed", true)
                .containsEntry("business_date", java.sql.Date.valueOf(DAY));
        assertThat(notices(manager)).isOne();
    }

    /** #6 — đối soát ca chốt tay và ca tự chốt (BRANCH_MANAGER nhập số thực đếm): {@code RECONCILED}, chênh lệch, audit. */
    @Test
    void reconcilingAManuallyAndAnAutoClosedShift() {
        long manual = open(false);
        paymentSql(branch, newCustomer(), manual, "CASH", 100_000, receptionist);
        data(close(manual, 100_000), HttpStatus.OK);
        // ca của lễ tân thứ hai, tự chốt: chưa có số thực đếm
        long second = receptionist(branch);
        long auto = openShiftSql(branch, second, false, DAY, at(DAY, 9, 0));
        paymentSql(branch, newCustomer(), auto, "CASH", 50_000, second);
        runAt(at(DAY, 18, 30));
        assertThat(status(auto)).isEqualTo("CLOSED");
        refreshTokens();

        data(reconcile(manual, null), HttpStatus.OK);
        assertRule(reconcile(auto, null), "BR-TG-05");
        assertThat(status(auto)).isEqualTo("CLOSED");
        data(reconcile(auto, 45_000L), HttpStatus.OK);

        assertThat(shiftRow(manual)).containsEntry("status", "RECONCILED").containsEntry("difference", 0L);
        assertThat(shiftRow(auto)).containsEntry("status", "RECONCILED").containsEntry("difference", -5_000L);
        assertThat(audits(manual)).isOne();
        assertThat(audits(auto)).isOne();
    }

    /** Mỗi lễ tân tối đa một ca {@code OPEN}: mở ca thứ hai là 409 (docs/adr/0064 mục 1), không ghi gì. */
    @Test
    void openingASecondShiftIs409AndWritesNothing() {
        long shift = open(false);
        Map<String, Object> before = footprint(shift);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, receptionistToken,
                Map.of("afterHours", false));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cashier_shifts WHERE cashier_id = ?", Long.class,
                receptionist)).isOne();
        assertThat(footprint(shift)).isEqualTo(before);
    }

    // ================================================================ cặp sai → 409, không ghi gì

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OPEN", "CLOSED", "RECONCILED"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long shift = reach(source);
        Map<String, Object> before = footprint(shift);

        List<String> tried = switch (source) {
            case "OPEN" -> List.of("reconcile");
            case "CLOSED" -> List.of("close");
            default -> List.of("close", "reconcile");
        };
        for (String operation : tried) {
            ResponseEntity<Map<String, Object>> response = operation.equals("close") ? close(shift, 1_000)
                    : reconcile(shift, 1_000L);
            assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("statusCode", 409);
        }
        job.run(); // ST13 chỉ chạm ca OPEN: ca đã đóng không bị đụng lần nữa

        assertThat(footprint(shift)).isEqualTo(before);
    }

    // ================================================================ helpers

    private long reach(String source) {
        long shift = open(false);
        if (!source.equals("OPEN")) {
            data(close(shift, 0), HttpStatus.OK);
        }
        if (source.equals("RECONCILED")) {
            data(reconcile(shift, null), HttpStatus.OK);
        }
        return shift;
    }

    private long open(boolean afterHours) {
        return number(data(call(HttpMethod.POST, PATH, receptionistToken, Map.of("afterHours", afterHours)),
                HttpStatus.CREATED).get("shiftId"));
    }

    private ResponseEntity<Map<String, Object>> close(long shift, long countedCash) {
        return call(HttpMethod.POST, PATH + "/" + shift + "/close", receptionistToken,
                Map.of("countedCash", countedCash));
    }

    private ResponseEntity<Map<String, Object>> reconcile(long shift, Long countedCash) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("note", "Đã kiểm két");
        if (countedCash != null) {
            body.put("countedCash", countedCash);
        }
        return call(HttpMethod.POST, PATH + "/" + shift + "/reconcile", managerToken, body);
    }

    /** Phiên hết hạn theo cùng đồng hồ: mở lại token sau khi dời giờ (CLAUDE.md, mục Testing). */
    private void refreshTokens() {
        receptionistToken = token(receptionist);
        managerToken = token(manager);
    }

    private void runAt(Instant instant) {
        clock.set(instant);
        job.run();
    }

    private String status(long shift) {
        return (String) shiftRow(shift).get("status");
    }

    private long notices(long accountId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = 'CASHIER_SHIFT_AUTO_CLOSED_APP'
                AND recipient_account_id = ?""", Long.class, accountId);
    }

    private long audits(long shift) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'CASHIER_SHIFT_RECONCILED'
                AND entity_type = 'cashier_shifts' AND entity_id = ?""", Long.class, shift);
    }

    /** Dấu "không ghi gì": phiên bản dòng ca, số phiếu thu, số audit đối soát, số thông báo tự chốt. */
    private Map<String, Object> footprint(long shift) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("shift", jdbc.queryForObject("SELECT xmin::text FROM cashier_shifts WHERE id = ?", String.class, shift));
        rows.put("payments", jdbc.queryForObject("SELECT count(*) FROM payments WHERE cashier_shift_id = ?", Long.class,
                shift));
        rows.put("audits", audits(shift));
        rows.put("notices", notices(manager));
        return rows;
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(VN).toInstant();
    }
}
