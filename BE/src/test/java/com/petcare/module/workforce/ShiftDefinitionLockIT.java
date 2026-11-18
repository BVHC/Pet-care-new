package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Đồng thời trên ca làm việc (workforce-v1 #2–3; docs/adr/0138 mục khóa, 0123 mục 8; plan mục 8). Cách giữ một request
 * "đứng giữa chừng" khi đang giữ khóa dòng: trigger tạm {@code AFTER INSERT / UPDATE} gọi
 * {@code pg_advisory_xact_lock}, còn test giữ advisory lock đó ở một transaction khác — request dừng đúng lúc đã ghi
 * dòng (khóa dòng, chỉ mục unique đã có mục chưa commit) và chưa commit. Chứng minh: (1) {@code PATCH} giữ khóa xung đột
 * với {@code FOR SHARE} (bên ghi phân ca T59) nhưng <b>không</b> chặn kiểm FK của {@code INSERT shift_assignments};
 * (2) guard đọc dưới khóa; (3) đua tên → một bên 409, không 500. Era <b>năm 2047</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftDefinitionLockIT.TestBeans.class})
class ShiftDefinitionLockIT extends ShiftDefinitionItSupport {

    private static final Instant NOW = Instant.parse("2047-03-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2047, 3, 14);
    private static final long PAUSE_KEY = 20_470_001L;
    private static final long WAIT_SECONDS = 20;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(5);
    private long branch;
    private long manager;
    private long staff;
    private String token;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        manager = data.staff("BRANCH_MANAGER", branch);
        staff = data.staff("RECEPTIONIST", branch);
        token = token(manager);
    }

    @AfterEach
    void tearDown() {
        try {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_sd_pause ON shift_definitions");
            jdbc.execute("DROP FUNCTION IF EXISTS sd_test_pause()");
            pool.shutdownNow();
        } finally {
            finish();
        }
    }

    // ---------------------------------------------------------------- khóa của PATCH so với bên ghi phân ca

    @Test
    void patchHoldsALockThatBlocksForShareButNotTheForeignKeyCheckAndAWriterReadsTheCommittedFlag() throws Exception {
        long id = definition(branch, "Ca", "08:00", "12:00", true);
        pauseAfter("UPDATE", "NEW.id = " + id);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + id, token, "{'active':false}"));
        awaitPausedWriter();

        // PATCH đã ghi dòng và đang giữ khóa, chưa commit. (1) kiểm FK của INSERT phân ca chỉ lấy FOR KEY SHARE: không bị chặn.
        long probe = tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            return data.assignment(branch, staff, id, TODAY.plusDays(2), "CANCELLED", "MANAGER", manager);
        });
        assertThat(probe).isPositive();
        // (2) FOR SHARE tường minh (bên ghi phân ca của T59) bị chặn bởi khóa của PATCH.
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '700ms'");
            return jdbc.queryForObject("SELECT is_active FROM shift_definitions WHERE id = ? FOR SHARE", Boolean.class,
                    id);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("lock timeout");
        // (3) một bên ghi thật chờ khóa, rồi đọc lại dưới khóa: thấy cờ đã ngừng dùng do PATCH commit.
        Future<Boolean> writer = pool.submit(() -> tx.execute(status -> jdbc.queryForObject(
                "SELECT is_active FROM shift_definitions WHERE id = ? FOR SHARE", Boolean.class, id)));
        awaitRowLockWait();
        assertThat(writer.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(patch.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(writer.get(WAIT_SECONDS, TimeUnit.SECONDS)).isFalse();
        assertThat(row(id)).containsEntry("is_active", false);
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void anAssignmentCommittedFirstMakesTheDeactivationFailWithBrNs01() throws Exception {
        long id = definition(branch, "Ca", "08:00", "12:00", true);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // bên ghi phân ca (T59): khóa chia sẻ dòng ca, thêm phân ca SCHEDULED, giữ giao dịch
        Future<?> writer = pool.submit(() -> tx.execute(status -> {
            jdbc.queryForObject("SELECT id FROM shift_definitions WHERE id = ? FOR SHARE", Long.class, id);
            scheduled(branch, staff, id, TODAY.plusDays(1));
            inserted.countDown();
            await(release);
            return null;
        }));
        assertThat(inserted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<ResponseEntity<Map<String, Object>>> patch = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + id, token, "{'active':false}"));
        awaitRowLockWait();
        assertThat(patch.isDone()).as("PATCH phải chờ khóa của bên ghi phân ca").isFalse();

        release.countDown();
        writer.get(WAIT_SECONDS, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = patch.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertBusinessRule(response, "BR-NS-01");
        assertThat(message(response)).startsWith("Ca còn phân ca từ hôm nay trở đi");
        assertThat(row(id)).containsEntry("is_active", true);
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void twoUpdatesOfTheSameShiftRunOneAfterTheOtherAndTheSecondGuardsOnTheFirstsResult() throws Exception {
        long id = definition(branch, "Ca", "08:00", "12:00", true);
        pauseAfter("UPDATE", "NEW.id = " + id);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + id, token, "{'name':'Tên một'}"));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + id, token, "{'name':'Tên hai'}"));
        awaitRowLockWait();
        assertThat(second.isDone()).isFalse();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(row(id)).containsEntry("name", "Tên hai");
        // bản chụp "before" của lần hai là kết quả của lần một: guard và before đọc dưới khóa, không phải bản cũ
        assertThat(jdbc.queryForList("""
                SELECT before_data->>'name' FROM audit_logs
                WHERE entity_type = 'shift_definitions' AND entity_id = ? ORDER BY id""", String.class, id))
                .endsWith("Tên một").hasSize(2);
    }

    // ---------------------------------------------------------------- đua tên (unique lower(name))

    @Test
    void twoShiftsRenamedToTheSameNameAtOnceLeaveOneWinnerAndAConflictNot500() throws Exception {
        long winner = definition(branch, "Ca thắng", "08:00", "12:00", true);
        long loser = definition(branch, "Ca thua", "13:00", "17:00", true);
        String loserName = (String) row(loser).get("name");
        pauseAfter("UPDATE", "NEW.id = " + winner);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + winner, token, "{'name':'Tên đua'}"));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                () -> call(HttpMethod.PATCH, PATH + "/" + loser, token, "{'name':'tên ĐUA'}"));
        awaitRowLockWait();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map<String, Object>> conflict = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(row(loser)).containsEntry("name", loserName);
        assertThat(auditCount(loser)).as("rollback: không audit cho ca thua").isZero();
        assertThat(auditCount(winner)).isEqualTo(1);
    }

    @Test
    void twoCreatesOfTheSameNameAtOnceLeaveOneShiftAndAConflictNot500() throws Exception {
        pauseAfter("INSERT", "NEW.branch_id = " + branch + " AND NEW.name = 'Ca đua tạo'");
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdPauseLock(release);
        String body = "{'name':'Ca đua tạo','startTime':'08:00','endTime':'12:00'}";

        Future<ResponseEntity<Map<String, Object>>> first = pool.submit(() -> call(HttpMethod.POST, PATH, token, body));
        awaitPausedWriter();
        Future<ResponseEntity<Map<String, Object>>> second = pool.submit(() -> call(HttpMethod.POST, PATH, token, body));
        awaitRowLockWait();

        release.countDown();
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> conflict = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("CONCURRENCY_CONFLICT");
        assertThat(definitionCount(branch)).isEqualTo(1);
        assertThat(auditCountForBranch(branch)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- hạ tầng giữ request giữa chừng

    /** Trigger tạm: sau khi ghi một dòng thỏa {@code condition}, chờ advisory lock {@link #PAUSE_KEY}. */
    private void pauseAfter(String event, String condition) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sd_test_pause() RETURNS trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(%d);
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(PAUSE_KEY));
        jdbc.execute("DROP TRIGGER IF EXISTS trg_sd_pause ON shift_definitions");
        jdbc.execute("CREATE TRIGGER trg_sd_pause AFTER %s ON shift_definitions FOR EACH ROW WHEN (%s) "
                .formatted(event, condition) + "EXECUTE FUNCTION sd_test_pause()");
    }

    /** Giữ advisory lock trong một transaction cho tới khi {@code release} được mở; trả về khi đã nhả. */
    private Future<?> holdPauseLock(CountDownLatch release) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(" + PAUSE_KEY + ")");
            acquired.countDown();
            await(release);
            return null;
        }));
        assertThat(acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Đợi tới khi một transaction (request) đứng chờ advisory lock, tức đã ghi dòng và giữ khóa dòng. */
    private void awaitPausedWriter() throws InterruptedException {
        awaitCount("""
                SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = %d AND NOT granted"""
                .formatted(PAUSE_KEY), "request chưa dừng ở trigger tạm");
    }

    /** Đợi tới khi một câu lệnh về {@code shift_definitions} đang chờ khóa dòng / giao dịch khác. */
    private void awaitRowLockWait() throws InterruptedException {
        awaitCount("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND wait_event <> 'advisory'
                  AND query ILIKE '%shift_definitions%' AND pid <> pg_backend_pid()""", "chưa có câu lệnh chờ khóa dòng");
    }

    private void awaitCount(String sql, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject(sql, Integer.class);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(failure);
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
