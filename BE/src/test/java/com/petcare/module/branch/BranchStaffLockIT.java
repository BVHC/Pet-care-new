package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.service.BranchService;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * {@code BranchApi.lockForStaffChange} trên PostgreSQL 17 (docs/adr/0029, nợ D014; bảng race R1–R3 của kế hoạch
 * T14 phần 0): chỉ chạy trong transaction ghi của bên gọi, giữ khóa tới khi bên gọi commit / rollback, chặn thật
 * {@code activateBranch}, không ghi {@code branches}, luôn trả trạng thái mới dù persistence context đã có entity cũ,
 * khóa theo id tăng dần nên không deadlock với luồng khóa ngược thứ tự. Mỗi test dùng chi nhánh mới (tên UUID).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BranchStaffLockIT {

    /** Câu khóa của {@code lockStatusForStaffChange} và của {@code findByIdForUpdate} (Hibernate 6 sinh NO KEY). */
    private static final String WAITING_ON_BRANCH_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database() AND wait_event_type = 'Lock'
              AND query ILIKE '%from branches%for no key update%'""";

    @Autowired
    private BranchApi branchApi;

    @Autowired
    private BranchQueryApi branchQuery;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private BranchService branchService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void outsideTransactionFails() {
        long branch = branch("DRAFT");

        assertThatThrownBy(() -> branchApi.lockForStaffChange(List.of(branch)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void readOnlyTransactionFails() {
        long branch = branch("DRAFT");
        TransactionTemplate readOnly = tx();
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                branchApi.lockForStaffChange(List.of(branch))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
    }

    @Test
    void returnsStatusAndSkipsUnknownIds() {
        long draft = branch("DRAFT");
        long active = branch("ACTIVE");

        Map<Long, BranchStatus> locked = tx().execute(status ->
                branchApi.lockForStaffChange(List.of(active, Long.MAX_VALUE, draft)));

        assertThat(locked).containsExactly(Map.entry(draft, BranchStatus.DRAFT), Map.entry(active, BranchStatus.ACTIVE));
    }

    /** R2: kích hoạt chi nhánh (khóa {@code branches} trước khi đếm BRANCH_MANAGER) chờ tới khi bên giữ khóa commit. */
    @Test
    void lockHeldUntilCallerCommits() throws Exception {
        long branch = branch("DRAFT");

        Throwable activation = whileBranchesLocked(List.of(branch), true,
                () -> branchService.activateBranch(branch));

        assertThat(activation).as("kích hoạt chạy sau khi khóa được nhả, rồi mới kiểm BR-QT-04")
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-QT-04");
    }

    @Test
    void lockReleasedOnCallerRollback() throws Exception {
        long branch = branch("DRAFT");

        Throwable activation = whileBranchesLocked(List.of(branch), false,
                () -> branchService.activateBranch(branch));

        assertThat(activation).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-QT-04");
    }

    /** Câu khóa trả giá trị vô hướng: không entity nào bị dirty-check, commit không sinh {@code UPDATE branches}. */
    @Test
    void noUpdateOnCommit() {
        long branch = branch("ACTIVE");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT updated_at, xmin::text AS xmin FROM branches WHERE id = ?", branch);

        tx().executeWithoutResult(status -> branchApi.lockForStaffChange(List.of(branch)));

        assertThat(jdbc.queryForMap("SELECT updated_at, xmin::text AS xmin FROM branches WHERE id = ?", branch))
                .isEqualTo(before);
    }

    /**
     * R3: entity {@code Branch} đã nằm trong persistence context ({@code BranchQueryApi.findBranch} nạp bằng
     * {@code findById}) thì {@code findByIdForUpdate} trả lại instance cũ; câu native của {@code BranchApi} đọc lại DB.
     */
    @Test
    void lockReturnsFreshStatusAfterStaleRead() {
        long branch = branch("DRAFT");

        AtomicReference<BranchStatus> viaApi = new AtomicReference<>();
        AtomicReference<Object> viaEntity = new AtomicReference<>();
        tx().executeWithoutResult(status -> {
            assertThat(branchQuery.findBranch(branch)).get()
                    .extracting(BranchQueryApi.BranchSummary::status).isEqualTo(BranchStatus.DRAFT);

            runInOtherTransaction(() -> jdbc.update(
                    "UPDATE branches SET status = 'ACTIVE', activated_at = now(), updated_at = now() WHERE id = ?",
                    branch));

            viaEntity.set(branchRepository.findByIdForUpdate(branch).map(Branch::getStatus).orElseThrow());
            viaApi.set(branchApi.lockForStaffChange(List.of(branch)).get(branch));
        });

        assertThat(viaEntity.get()).as("bẫy persistence context — lý do không dùng findByIdForUpdate")
                .hasToString("DRAFT");
        assertThat(viaApi.get()).isEqualTo(BranchStatus.ACTIVE);
    }

    /**
     * R1: luồng khác đang giữ X rồi sẽ khóa thêm Y. {@code lockForStaffChange([Y, X])} sắp tăng dần nên chờ ở X mà
     * chưa giữ Y, luồng kia khóa Y được ngay — cả hai xong, không ai nhận deadlock {@code 40P01}. Bản không sắp xếp
     * sẽ giữ Y rồi chờ X, tạo chu trình chờ.
     */
    @Test
    void oppositeOrderDoesNotDeadlock() throws Exception {
        long x = branch("DRAFT");
        long y = branch("DRAFT");
        CountDownLatch xHeld = new CountDownLatch(1);
        CountDownLatch apiWaiting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> other = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR NO KEY UPDATE", Long.class, x);
                xHeld.countDown();
                await(apiWaiting);
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR NO KEY UPDATE", Long.class, y);
            }));
            assertThat(xHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Map<Long, BranchStatus>> api = pool.submit(() -> tx().execute(status ->
                    branchApi.lockForStaffChange(List.of(y, x))));
            awaitWaitingOnBranchLock();
            apiWaiting.countDown();

            other.get(30, TimeUnit.SECONDS);
            assertThat(api.get(30, TimeUnit.SECONDS)).containsOnlyKeys(x, y);
        } finally {
            apiWaiting.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Lỗi khóa đi qua proxy {@code MANDATORY} đánh dấu transaction bên gọi rollback-only: bắt lỗi rồi commit thì nhận
     * {@code UnexpectedRollbackException}. Ghi lại bẫy để bên gọi không bắt lỗi của {@code BranchApi} (docs/adr/0029).
     */
    @Test
    void lockFailureMarksCallerRollbackOnly() throws Exception {
        long branch = branch("DRAFT");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR UPDATE", Long.class, branch);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                try {
                    branchApi.lockForStaffChange(List.of(branch));
                } catch (RuntimeException ex) {
                    caught.set(ex);
                }
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(caught.get()).isInstanceOf(PessimisticLockingFailureException.class);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------------------------------------------------------------- hỗ trợ

    /**
     * Transaction A khóa {@code branchIds} qua {@code BranchApi} rồi giữ; {@code contender} chạy ở thread khác và phải
     * đứng chờ khóa ({@code pg_stat_activity}) trước khi A commit ({@code commit = true}) hoặc rollback. Trả lỗi mà
     * {@code contender} ném (hoặc {@code null}).
     */
    private Throwable whileBranchesLocked(List<Long> branchIds, boolean commit, Runnable contender) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                branchApi.lockForStaffChange(branchIds);
                held.countDown();
                await(release);
                if (!commit) {
                    status.setRollbackOnly();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> call = pool.submit(() -> {
                try {
                    contender.run();
                    return null;
                } catch (Throwable ex) {
                    return ex;
                }
            });
            awaitWaitingOnBranchLock();
            assertThat(call.isDone()).as("phải chờ khóa dòng branches").isFalse();
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            return call.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void runInOtherTransaction(Runnable action) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> tx().executeWithoutResult(status -> action.run())).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitWaitingOnBranchLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_BRANCH_LOCK, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("phải có câu đứng chờ khóa branches; pg_stat_activity = " + jdbc.queryForList("""
                        SELECT wait_event_type, state, query FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid()"""));
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long branch(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status, activated_at)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?,
                        CASE WHEN ? = 'ACTIVE' THEN now() END) RETURNING id
                """, Long.class, "BranchStaffLockIT " + UUID.randomUUID(), status, status);
    }
}
