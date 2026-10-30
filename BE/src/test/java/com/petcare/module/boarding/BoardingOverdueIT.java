package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.job.BoardingOverdueJob;
import com.petcare.module.identity.service.SystemConfigService;

/**
 * ST15 — lưu trú quá hạn đón (BR-LT-10, Đặt chỗ#7; docs/adr/0086). Era 1988: lượt theo dõi quét mọi đặt chỗ
 * {@code OVERDUE} không có cận dưới nên IT này phải cũ hơn mọi era khác. Đặt chỗ nhận 08/06, trả dự kiến 12/06; giờ trả
 * quy định 12:00 giờ Việt Nam = 05:00Z. Job được gọi thẳng ({@code cron "-"} trong profile test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingOverdueClockConfig.class})
class BoardingOverdueIT extends BoardingItSupport {

    private static final String BEFORE_NOON = "1988-06-12T04:59:59Z";
    private static final String AFTER_NOON = "1988-06-12T05:03:00Z";
    private static final String NEXT_DAY = "1988-06-13T05:01:00Z";
    private static final String SEVEN_DAYS = "1988-06-19T05:01:00Z";

    @Autowired
    private BoardingOverdueJob job;
    @Autowired
    private SystemConfigService configs;

    private long branch;
    private long type;
    private long kennel;
    private long receptionistId;
    private long customerId;
    private long customerAccount;
    private long pet;
    private long stay;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        kennel = newKennel(branch, type, "C-01", "OCCUPIED");
        receptionistId = newStaff("RECEPTIONIST", branch);
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        customerId = online[1];
        pet = newPet(customerId, "DOG");
        stay = checkedIn(customerId, pet, kennel);
    }

    private long checkedIn(long customer, long petId, long kennelId) {
        return newBooking(customer, petId, branch, type, LocalDate.parse("1988-06-08"), LocalDate.parse("1988-06-12"),
                "CHECKED_IN", kennelId, receptionistId);
    }

    private void run(String instant) {
        clock.set(Instant.parse(instant));
        job.run();
    }

    private String code(long bookingId) {
        return jdbc.queryForObject("SELECT code FROM boarding_bookings WHERE id = ?", String.class, bookingId);
    }

    private List<Map<String, Object>> outbox(long bookingId, String templatePrefix) {
        return jdbc.queryForList("""
                SELECT template_code, channel, recipient_account_id FROM notification_outbox
                WHERE payload ->> 'ma_dat_cho' = ? AND template_code LIKE ?""", code(bookingId), templatePrefix + "%");
    }

    private int taskCount(long bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE boarding_booking_id = ? "
                + "AND task_type = 'PICKUP_OVERDUE'", Integer.class, bookingId);
    }

    @Test
    void staysCheckedInBeforeNoonOfTheCheckOutDate() {
        run(BEFORE_NOON);

        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
        assertThat(outbox(stay, "BOARDING_OVERDUE")).isEmpty();
    }

    @Test
    void becomesOverdueAtNoonWithSinceAtNoonAndNotifiesTheCustomerOnce() {
        run(AFTER_NOON);

        assertThat(statusOf(stay)).isEqualTo("OVERDUE");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT overdue_since, last_overdue_notice_on FROM boarding_bookings WHERE id = ?", stay);
        assertThat(((Timestamp) row.get("overdue_since")).toInstant()).isEqualTo(Instant.parse("1988-06-12T05:00:00Z"));
        assertThat(row.get("last_overdue_notice_on").toString()).isEqualTo("1988-06-12");
        assertThat(outbox(stay, "BOARDING_OVERDUE")).extracting(r -> r.get("template_code"))
                .containsExactlyInAnyOrder("BOARDING_OVERDUE", "BOARDING_OVERDUE_APP");

        run("1988-06-12T05:30:00Z");

        assertThat(outbox(stay, "BOARDING_OVERDUE")).as("chạy lại cùng ngày không gửi lại").hasSize(2);
        assertThat(taskCount(stay)).isZero();
    }

    @Test
    void nextDayNoonSendsSecondNoticeAndCreatesExactlyOnePickupTask() {
        run(AFTER_NOON);

        run(NEXT_DAY);
        run("1988-06-13T05:10:00Z");

        assertThat(outbox(stay, "BOARDING_OVERDUE")).hasSize(4);
        assertThat(taskCount(stay)).isEqualTo(1);
        Map<String, Object> task = jdbc.queryForMap("""
                SELECT branch_id, customer_id, pet_id, due_date, status FROM care_tasks
                WHERE boarding_booking_id = ? AND task_type = 'PICKUP_OVERDUE'""", stay);
        assertThat(((Number) task.get("branch_id")).longValue()).isEqualTo(branch);
        assertThat(((Number) task.get("customer_id")).longValue()).isEqualTo(customerId);
        assertThat(((Number) task.get("pet_id")).longValue()).isEqualTo(pet);
        assertThat(task.get("due_date").toString()).isEqualTo("1988-06-13");
        assertThat(task.get("status")).isEqualTo("OPEN");
    }

    @Test
    void managerIsNotifiedOnceAfterSevenDays() {
        long manager = newStaff("BRANCH_MANAGER", branch);
        run(AFTER_NOON);

        run(SEVEN_DAYS);
        run("1988-06-19T05:20:00Z");

        List<Map<String, Object>> rows = outbox(stay, "BOARDING_OVERDUE_MANAGER");
        assertThat(rows).singleElement().satisfies(r -> assertThat(((Number) r.get("recipient_account_id")).longValue())
                .isEqualTo(manager));
        assertThat(jdbc.queryForObject("SELECT manager_notified_at IS NOT NULL FROM boarding_bookings WHERE id = ?",
                Boolean.class, stay)).isTrue();
    }

    @Test
    void bookingWithPaidOrderIsNotMarkedOverdue() {
        long shift = jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, false, DATE '1988-06-12', 'OPEN', ?) RETURNING id""", Long.class, branch, receptionistId,
                Timestamp.from(Instant.parse("1988-06-12T01:00:00Z")));
        long payment = jdbc.queryForObject("""
                INSERT INTO payments (code, branch_id, customer_id, cashier_shift_id, method, amount, received_by,
                                      paid_at)
                VALUES (?, ?, ?, ?, 'CASH', 600000, ?, now()) RETURNING id""", Long.class,
                "PT-IT-" + java.util.UUID.randomUUID().toString().substring(0, 12), branch, customerId, shift,
                receptionistId);
        jdbc.update("""
                INSERT INTO orders (code, source, customer_id, branch_id, boarding_booking_id, payment_id, status,
                                    total_amount, created_by, pending_at, paid_at)
                VALUES (?, 'BOARDING', ?, ?, ?, ?, 'PAID', 600000, ?, now(), now())""",
                "DH-IT-" + java.util.UUID.randomUUID().toString().substring(0, 10), customerId, branch, stay, payment,
                receptionistId);

        run(AFTER_NOON);

        assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
        assertThat(outbox(stay, "BOARDING_OVERDUE")).isEmpty();
    }

    @Test
    void lockedBookingIsSkippedThenMarkedOnTheNextRun() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM boarding_bookings WHERE id = ? FOR NO KEY UPDATE", Long.class,
                            stay);
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
        try {
            run(AFTER_NOON);
            assertThat(statusOf(stay)).as("dòng đang bị giữ khóa: bỏ qua, không chờ").isEqualTo("CHECKED_IN");
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }

        run("1988-06-12T05:08:00Z");

        assertThat(statusOf(stay)).isEqualTo("OVERDUE");
    }

    @Test
    void oneFailingBookingDoesNotBlockTheOthers() {
        long other = checkedIn(newCounterCustomer(null), newPet(customerId, "DOG"), newKennel(branch, type, "C-02",
                "OCCUPIED"));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION overdue_it_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'overdue it forced failure'; END $$""");
        jdbc.execute("CREATE TRIGGER overdue_it_fail BEFORE UPDATE ON boarding_bookings FOR EACH ROW WHEN (NEW.id = "
                + stay + ") EXECUTE FUNCTION overdue_it_fail()");
        try {
            run(AFTER_NOON);

            assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
            assertThat(statusOf(other)).isEqualTo("OVERDUE");
        } finally {
            jdbc.execute("DROP TRIGGER overdue_it_fail ON boarding_bookings");
            jdbc.execute("DROP FUNCTION overdue_it_fail()");
        }
    }

    @Test
    void capacityWarningListsTheBookedBookingsThatBecomeShort() {
        long receptionist = newStaff("RECEPTIONIST", branch);
        long counter = newCounterCustomer(null);
        long affected = newBooking(counter, newPet(counter, "DOG"), branch, type, LocalDate.parse("1988-06-13"),
                LocalDate.parse("1988-06-14"), "BOOKED", receptionistId);

        run(AFTER_NOON);

        assertThat(statusOf(stay)).isEqualTo("OVERDUE");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT recipient_account_id, CAST(payload AS text) AS payload FROM notification_outbox
                WHERE template_code = 'BOARDING_CAPACITY_WARNING_APP' AND payload ->> 'ma_dat_cho_qua_han' = ?""",
                code(stay));
        assertThat(rows).extracting(r -> ((Number) r.get("recipient_account_id")).longValue())
                .contains(receptionist, receptionistId);
        assertThat(rows.get(0).get("payload").toString()).contains(code(affected));
    }

    @Test
    void counterCustomerWithoutEmailGetsTaskButNoNotice() {
        long counter = newCounterCustomer(null);
        long counterStay = checkedIn(counter, newPet(counter, "DOG"), newKennel(branch, type, "C-02", "OCCUPIED"));
        run(AFTER_NOON);

        run(NEXT_DAY);

        assertThat(statusOf(counterStay)).isEqualTo("OVERDUE");
        assertThat(outbox(counterStay, "BOARDING_OVERDUE")).isEmpty();
        assertThat(taskCount(counterStay)).isEqualTo(1);
    }

    @Test
    void longDisabledJobCatchesUpInOneRun() {
        long manager = newStaff("BRANCH_MANAGER", branch);

        run("1988-06-20T05:03:00Z");

        assertThat(statusOf(stay)).isEqualTo("OVERDUE");
        assertThat(jdbc.queryForObject("SELECT overdue_since FROM boarding_bookings WHERE id = ?", Timestamp.class, stay)
                .toInstant()).isEqualTo(Instant.parse("1988-06-12T05:00:00Z"));
        assertThat(taskCount(stay)).isEqualTo(1);
        assertThat(outbox(stay, "BOARDING_OVERDUE_MANAGER")).extracting(r -> r.get("recipient_account_id"))
                .containsExactly(manager);
    }

    @Test
    void checkoutTimeChangeAppliesFromTheNextRun() {
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'boarding.checkout_time'",
                String.class);
        try {
            jdbc.update("UPDATE system_configs SET value = '15:00' WHERE key = 'boarding.checkout_time'");
            configs.reload();

            run("1988-06-12T05:30:00Z");          // 12:30 VN: chưa tới 15:00

            assertThat(statusOf(stay)).isEqualTo("CHECKED_IN");
            run("1988-06-12T08:01:00Z");          // 15:01 VN
            assertThat(statusOf(stay)).isEqualTo("OVERDUE");
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = 'boarding.checkout_time'", original);
            configs.reload();
        }
    }

    @Test
    void scanUsesThePartialIndexOnRealisticData() {
        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.update("""
                    INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, check_in_date,
                            check_out_date, nightly_price, status, channel, booked_by, cancel_source)
                    SELECT 'LTX-' || g, ?, ?, ?, ?, DATE '1988-05-01' + (g % 30), DATE '1988-05-02' + (g % 30),
                           150000, 'CANCELLED', 'COUNTER', ?, 'STAFF'
                    FROM generate_series(1, 20000) g""", customerId, pet, branch, type, receptionistId);
            jdbc.execute("ANALYZE boarding_bookings");
            List<String> rows = jdbc.queryForList("""
                    EXPLAIN SELECT id AS id, pet_id AS petId, check_out_date AS checkOutDate FROM boarding_bookings
                    WHERE status = 'CHECKED_IN' AND check_out_date <= DATE '1988-06-12'
                    ORDER BY check_out_date, id LIMIT 100""", String.class);
            status.setRollbackOnly();
            return String.join("\n", rows);
        });

        assertThat(plan).contains("ix_boarding_bookings_checked_in_due");
    }
}
