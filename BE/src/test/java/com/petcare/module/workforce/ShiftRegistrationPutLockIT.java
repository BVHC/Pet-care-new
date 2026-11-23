package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Khóa của đăng ký ca theo tuần (workforce-v1 #15; docs/adr/0149 mục "Đăng ký tuần — ghi #15", ADR-0123 mục 8) trên
 * Postgres thật. Một transaction khác giữ {@code FOR NO KEY UPDATE} trên dòng {@code accounts} của nhân viên (hoặc một
 * advisory lock mà trigger tạm chờ), các request xếp hàng chờ <b>theo thứ tự khởi động</b> nên kết quả là xác định:
 * (a) hai {@code PUT} của cùng nhân viên chạy lần lượt và lần sau thắng; (b) lưới DB: một phân ca chèn lọt giữa lúc
 * {@code PUT} đã kiểm xong và lúc chèn → 409 và rút cả hủy, cả từ chối yêu cầu; (c) {@code PUT} ∥ ngừng dùng ca
 * ({@code PATCH} #3) theo cả hai thứ tự; (d) {@code PUT} ∥ phân hộ #5 chồng giờ theo cả hai thứ tự; (e) tài khoản bị
 * vô hiệu hóa / khóa / đổi vai trò, hoặc qua hạn 00:00 thứ Hai, trong lúc {@code PUT} chờ khóa; (f) không chặn người
 * khác và ca của chi nhánh khác; (g) commit hiện ra cùng lúc. Era <b>năm 2088</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationPutLockIT.TestBeans.class})
class ShiftRegistrationPutLockIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2088-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2088, 3, 15));
    private static final long WAIT_SECONDS = 20;
    private static final long PAUSE_KEY = 20_880_001L;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private long branchA;
    private long branchB;
    private long manager;
    private long staff;
    private long otherStaff;
    private long morning;
    private long afternoon;
    private long noon;
    private long spare;
    private long foreign;
    private String staffToken;
    private String otherToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        staff = data.staff("RECEPTIONIST", branchA);
        otherStaff = data.staff("VET", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
        noon = definition(branchA, "Ca giữa", "11:00", "14:00", true);
        spare = definition(branchA, "Ca phụ", "18:00", "20:00", true);
        foreign = definition(branchB, "Ca chi nhánh khác", "08:00", "12:00", true);
        staffToken = token(staff);
        otherToken = token(otherStaff);
        managerToken = token(manager);
    }

    @AfterEach
    void tearDown() {
        try {
            dropTriggers();
            pool.shutdownNow();
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- (a) hai PUT của cùng nhân viên

    @Test
    void twoPutsOfTheSameStaffMemberRunOneAfterTheOtherAndTheLaterOneWins() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);
        String first = body(tenItems());
        List<String> secondItems = new ArrayList<>(tenItems());
        secondItems.set(9, item(WEEK.plusDays(5), morning)); // thay (Thứ Sáu, ca chiều) bằng (Thứ Bảy, ca sáng)

        Future<ResponseEntity<Map<String, Object>>> a = pool.submit(() -> put(staffToken, WEEK, first));
        awaitAccountWaits(1);
        Future<ResponseEntity<Map<String, Object>>> b = pool.submit(() -> put(staffToken, WEEK, body(secondItems)));
        awaitAccountWaits(2);
        assertThat(a.isDone() || b.isDone()).as("cả hai phải chờ khóa tài khoản").isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(a.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(b.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> scheduled = scheduledRowsOf(staff);
        assertThat(scheduled).hasSize(10);
        assertThat(scheduled).extracting(row -> row.get("work_date").toString() + ":" + row.get("shift_definition_id"))
                .contains(WEEK.plusDays(5) + ":" + morning).doesNotContain(WEEK.plusDays(4) + ":" + afternoon);
        List<Map<String, Object>> cancelled = rowsOf(staff).stream().filter(row -> "CANCELLED".equals(row.get("status"))).toList();
        assertThat(cancelled).hasSize(1).allSatisfy(row -> assertThat(row)
                .containsEntry("reason", SELF_EDIT_REASON).containsEntry("status_changed_by", staff));
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (b) lưới DB: phân ca lọt giữa chừng → 409

    @Test
    void aRowSlippedInAfterTheChecksTripsTheUniqueIndexAndRollsBackTheCancelAndTheRequestClosing() throws Exception {
        List<Long> seeded = seedTen(staff);
        long droppedRequest = pendingRequest(seeded.get(9), staff);
        installPauseOnSelfInsert(staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> advisory = holdAdvisory(release);
        List<String> items = new ArrayList<>(tenItems());
        items.set(9, item(WEEK.plusDays(5), morning));

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, body(items)));
        awaitAdvisoryWaiters(1);
        long managed = data.assignment(branchA, staff, morning, WEEK.plusDays(5), "SCHEDULED", "MANAGER", null);
        release.countDown();
        advisory.get(WAIT_SECONDS, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> conflict = put.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(assignmentRow(seeded.get(9))).as("lần hủy phải được rút").containsEntry("status", "SCHEDULED")
                .containsEntry("reason", null);
        assertThat(requestRow(droppedRequest)).as("việc đóng yêu cầu phải được rút").containsEntry("status", "PENDING")
                .containsEntry("closed_at", null).containsEntry("decision_note", null);
        assertThat(scheduledRowsOf(staff)).hasSize(11);
        assertThat(rowsOf(staff).stream().filter(row -> "SELF".equals(row.get("source"))
                && "SCHEDULED".equals(row.get("status")) && (WEEK.plusDays(5) + "").equals(row.get("work_date"))))
                .as("không có phân ca SELF nào cho cặp đã bị chiếm").isEmpty();
        assertThat(assignmentRow(managed)).containsEntry("status", "SCHEDULED").containsEntry("source", "MANAGER");
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (c) PUT ∥ ngừng dùng ca (PATCH #3)

    @Test
    void aDeactivationHeldBeforeAPutMakesThePutFailBecauseTheShiftIsNoLongerInUse() throws Exception {
        installPauseOnDefinitionDeactivation(spare);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> advisory = holdAdvisory(release);

        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(this::deactivateSpare);
        awaitAdvisoryWaiters(1);
        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, bodyWithSpare()));
        awaitRowLockWaits("shift_definitions", 1);
        assertThat(put.isDone()).as("PUT phải chờ FOR SHARE ca mà PATCH đang giữ").isFalse();

        release.countDown();
        advisory.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(patch.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> rejected = put.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(rejected, "BR-NS-11");
        assertThat(message(rejected)).contains("đã ngừng dùng");
        assertThat(assignmentCountOfStaff(staff)).as("PUT không lưu gì").isZero();
        assertLocksReleased();
    }

    @Test
    void aPutHeldBeforeADeactivationBlocksItAndThePatchThenFailsBecauseTheShiftIsInUse() throws Exception {
        installPauseOnSelfInsert(staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> advisory = holdAdvisory(release);

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, bodyWithSpare()));
        awaitAdvisoryWaiters(1);
        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(this::deactivateSpare);
        awaitRowLockWaits("shift_definitions", 1);
        assertThat(patch.isDone()).as("PATCH phải chờ FOR SHARE của PUT").isFalse();

        release.countDown();
        advisory.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(put.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> refused = patch.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(refused, "BR-NS-01");
        assertThat(row(spare)).containsEntry("is_active", true);
        assertThat(scheduledRowsOf(staff)).hasSize(10);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (d) PUT ∥ phân hộ #5

    @Test
    void aPutQueuedBeforeAnOverlappingManagerAssignmentWinsAndTheAssignmentIs400() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, body(tenItems())));
        awaitAccountWaits(1);
        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(this::assignNoonOnMonday);
        awaitAccountWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(put.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertBusinessRule(assign.get(WAIT_SECONDS, TimeUnit.SECONDS), "BR-NS-02");
        assertThat(scheduledRowsOf(staff)).hasSize(10).allSatisfy(row -> assertThat(row).containsEntry("source", "SELF"));
        assertLocksReleased();
    }

    @Test
    void aManagerAssignmentQueuedBeforeAPutWinsAndThePutIs400BecauseOfTheOverlapAndSavesNothing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> assign = pool.submit(this::assignNoonOnMonday);
        awaitAccountWaits(1);
        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, body(tenItems())));
        awaitAccountWaits(2);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(assign.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map<String, Object>> rejected = put.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(rejected, "BR-NS-11");
        assertThat(message(rejected)).contains("chồng giờ với phân ca do quản lý xếp");
        assertThat(rowsOf(staff)).hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("source", "MANAGER"));
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (e) tài khoản đổi trong lúc chờ khóa

    @Test
    void anAccountDisabledWhileThePutWaitsForTheLockIs403AndNothingIsSaved() throws Exception {
        assertForbiddenAfter("UPDATE accounts SET status = 'DISABLED' WHERE id = ?");
    }

    @Test
    void anAccountLockedWhileThePutWaitsForTheLockIs403AndNothingIsSaved() throws Exception {
        assertForbiddenAfter("UPDATE accounts SET is_locked = true WHERE id = ?");
    }

    @Test
    void aRoleChangedWhileThePutWaitsForTheLockIs403AndNothingIsSaved() throws Exception {
        assertForbiddenAfter("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?");
    }

    @Test
    void theDeadlinePassingWhileThePutWaitsForTheLockIs400BecauseTheClockIsReadAfterTheLock() throws Exception {
        Instant deadline = WEEK.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        clock.set(deadline.minusSeconds(3_600));
        String late = token(staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccount(release);

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(late, WEEK, body(tenItems())));
        awaitAccountWaits(1);
        clock.set(deadline.plusSeconds(1));
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = put.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).contains("quá hạn");
        assertThat(assignmentCountOfStaff(staff)).isZero();
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (f) không chặn người khác

    @Test
    void aPutHeldMidWayBlocksNeitherAnotherStaffMembersPutNorAnotherBranchsShiftEdit() throws Exception {
        installPauseOnSelfInsert(staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> advisory = holdAdvisory(release);

        Future<ResponseEntity<Map<String, Object>>> held = pool.submit(() -> put(staffToken, WEEK, body(tenItems())));
        awaitAdvisoryWaiters(1);

        ResponseEntity<Map<String, Object>> others = pool.submit(() -> put(otherToken, WEEK, body(tenItems())))
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        ResponseEntity<Map<String, Object>> rename = pool.submit(() -> call(HttpMethod.PATCH, PATH + "/" + foreign,
                managerTokenOfBranchB(), "{'name':'Ca đổi tên " + java.util.UUID.randomUUID() + "'}"))
                .get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(others.getStatusCode().value()).as("PUT của người khác không bị chặn").isEqualTo(200);
        assertThat(rename.getStatusCode().value()).as("sửa ca của chi nhánh khác không bị chặn").isEqualTo(200);
        assertThat(held.isDone()).as("PUT bị giữ vẫn đang chờ").isFalse();
        release.countDown();
        advisory.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(held.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- (g) commit hiện ra cùng lúc

    @Test
    void theCancelTheRequestClosingAndTheNewRowBecomeVisibleTogetherOnlyAfterTheCommit() throws Exception {
        List<Long> seeded = seedTen(staff);
        long droppedRequest = pendingRequest(seeded.get(9), staff);
        installPauseOnSelfInsert(staff);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> advisory = holdAdvisory(release);
        List<String> items = new ArrayList<>(tenItems());
        items.set(9, item(WEEK.plusDays(5), morning));

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, body(items)));
        awaitAdvisoryWaiters(1);

        // PUT đã hủy phân ca và đóng yêu cầu (đã flush) nhưng chưa commit: bên ngoài vẫn thấy toàn bộ trạng thái cũ.
        assertThat(assignmentRow(seeded.get(9))).containsEntry("status", "SCHEDULED").containsEntry("reason", null);
        assertThat(requestRow(droppedRequest)).containsEntry("status", "PENDING");
        assertThat(scheduledRowsOf(staff)).hasSize(10);
        assertThat(put.isDone()).isFalse();

        release.countDown();
        advisory.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(put.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(assignmentRow(seeded.get(9))).containsEntry("status", "CANCELLED").containsEntry("reason", SELF_EDIT_REASON);
        assertThat(requestRow(droppedRequest)).containsEntry("status", "REJECTED").containsEntry("decision_note", EDITED_NOTE);
        assertThat(scheduledRowsOf(staff)).hasSize(10).anySatisfy(row -> assertThat(row)
                .containsEntry("work_date", WEEK.plusDays(5).toString()).containsEntry("shift_definition_id", morning));
        assertLocksReleased();
    }

    // ---------------------------------------------------------------- hạ tầng

    private void assertForbiddenAfter(String sql) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdStaffAccountThen(release, () -> jdbc.update(sql, staff));

        Future<ResponseEntity<Map<String, Object>>> put = pool.submit(() -> put(staffToken, WEEK, body(tenItems())));
        awaitAccountWaits(1);
        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = put.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(assignmentCountOfStaff(staff)).as("không ghi gì").isZero();
        assertLocksReleased();
    }

    private ResponseEntity<Map<String, Object>> deactivateSpare() {
        return call(HttpMethod.PATCH, PATH + "/" + spare, managerToken, "{'active':false}");
    }

    private ResponseEntity<Map<String, Object>> assignNoonOnMonday() {
        return call(HttpMethod.POST, ASSIGNMENTS, managerToken, body(staff, noon, WEEK.toString()));
    }

    private String managerTokenOfBranchB() {
        return token(data.staff("BRANCH_MANAGER", branchB));
    }

    /** 10 mục như {@code ShiftRegistrationPutIT}; mục thứ 10 (Thứ Sáu, ca chiều) được thay bằng ca phụ khi cần. */
    private String bodyWithSpare() {
        List<String> items = new ArrayList<>(tenItems());
        items.set(9, item(WEEK.plusDays(4), spare));
        return body(items);
    }

    private List<String> tenItems() {
        List<String> items = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            items.add(item(WEEK.plusDays(day), morning));
            items.add(item(WEEK.plusDays(day), afternoon));
        }
        return items;
    }

    private static String body(List<String> items) {
        return itemsBody(items.toArray(String[]::new));
    }

    private List<Long> seedTen(long staffId) {
        List<Long> ids = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            ids.add(data.assignment(branchA, staffId, morning, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
            ids.add(data.assignment(branchA, staffId, afternoon, WEEK.plusDays(day), "SCHEDULED", "SELF", null));
        }
        return ids;
    }

    private Future<?> holdStaffAccount(CountDownLatch release) throws InterruptedException {
        return holdStaffAccountThen(release, () -> null);
    }

    /** Giữ {@code FOR NO KEY UPDATE} trên tài khoản của {@code staff}; sau {@code release} chạy {@code afterRelease} rồi commit. */
    private Future<?> holdStaffAccountThen(CountDownLatch release, Supplier<Object> afterRelease)
            throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", Long.class, staff);
            acquired.countDown();
            await(release);
            return afterRelease.get();
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Giữ một advisory lock tới khi {@code release}: trigger tạm chờ khóa này nên request bị dừng giữa chừng. */
    private Future<?> holdAdvisory(CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, PAUSE_KEY);
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Trigger tạm: INSERT phân ca SELF của {@code staffId} dừng ở advisory lock, sau khi PUT đã hủy và đóng yêu cầu. */
    private void installPauseOnSelfInsert(long staffId) {
        createPauseFunction();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srp_pause_insert ON shift_assignments");
        jdbc.execute("""
                CREATE TRIGGER trg_srp_pause_insert BEFORE INSERT ON shift_assignments FOR EACH ROW
                WHEN (NEW.staff_id = %d AND NEW.source = 'SELF') EXECUTE FUNCTION srp_test_pause()""".formatted(staffId));
    }

    /** Trigger tạm: PATCH ngừng dùng ca dừng ngay sau UPDATE (đang giữ FOR NO KEY UPDATE dòng ca). */
    private void installPauseOnDefinitionDeactivation(long definitionId) {
        createPauseFunction();
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srp_pause_definition ON shift_definitions");
        jdbc.execute("""
                CREATE TRIGGER trg_srp_pause_definition AFTER UPDATE ON shift_definitions FOR EACH ROW
                WHEN (NEW.id = %d AND NOT NEW.is_active) EXECUTE FUNCTION srp_test_pause()""".formatted(definitionId));
    }

    /** {@code RETURN NEW}: với trigger BEFORE INSERT, {@code RETURN NULL} sẽ hủy luôn dòng được chèn (Hibernate báo 500). */
    private void createPauseFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION srp_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
    }

    private void dropTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srp_pause_insert ON shift_assignments");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_srp_pause_definition ON shift_definitions");
        jdbc.execute("DROP FUNCTION IF EXISTS srp_test_pause()");
    }

    private void awaitAccountWaits(int count) throws InterruptedException {
        awaitWaiters("wait_event_type = 'Lock' AND query ILIKE '%accounts%'", count, "khóa dòng của accounts");
    }

    /** Chờ khóa dòng (transactionid / tuple) trên bảng; không đếm advisory lock của trigger tạm. */
    private void awaitRowLockWaits(String table, int count) throws InterruptedException {
        awaitWaiters("wait_event_type = 'Lock' AND wait_event IN ('transactionid', 'tuple') AND query ILIKE '%" + table
                + "%'", count, "khóa dòng của " + table);
    }

    private void awaitAdvisoryWaiters(int count) throws InterruptedException {
        awaitWaiters("wait_event_type = 'Lock' AND wait_event = 'advisory'", count, "advisory lock của trigger tạm");
    }

    private void awaitWaiters(String predicate, int count, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE " + predicate + " AND pid <> pg_backend_pid()",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("chưa có " + count + " câu lệnh chờ " + what);
    }

    /** Sau khi mọi request xong, khóa dòng tài khoản, ca và mọi phân ca của {@code staff} lấy được ngay, tức không bị giữ. */
    private void assertLocksReleased() {
        assertThat(canLockNowait("accounts", staff)).isTrue();
        assertThat(canLockNowait("shift_definitions", morning)).isTrue();
        assertThat(canLockNowait("shift_definitions", spare)).isTrue();
        for (long id : assignmentIdsOfStaff(staff)) {
            assertThat(canLockNowait("shift_assignments", id)).as("phân ca %d", id).isTrue();
        }
    }

    private boolean canLockNowait(String table, long id) {
        try {
            tx.execute(status -> jdbc.queryForObject(
                    "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", Long.class, id));
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
