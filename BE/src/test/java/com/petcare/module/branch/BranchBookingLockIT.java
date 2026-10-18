package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
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
 * {@code BranchApi.lockForBooking} trên PostgreSQL 17 (docs/adr/0041): chỉ chạy trong transaction ghi của bên gọi, giữ
 * dòng {@code branches} tới khi bên gọi commit / rollback, chặn đúng khóa mà mọi luồng ghi của branch lấy
 * ({@code findByIdForUpdate}: kích hoạt, giờ mở cửa, ngày nghỉ, quota, bật dịch vụ — race R2, R3) và chặn lượt đặt khác
 * của cùng chi nhánh (R1), không chặn chi nhánh khác, không ghi {@code branches}, trả trạng thái mới dù persistence
 * context đã có entity cũ. Mỗi test dùng chi nhánh mới (tên UUID, {@code DRAFT} trừ khi cần {@code ACTIVE}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BranchBookingLockIT {

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

        assertThatThrownBy(() -> branchApi.lockForBooking(branch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** Gọi thẳng repository ngoài transaction thì lỗi, không âm thầm khóa rồi nhả ngay trong autocommit. */
    @Test
    void repositoryLockOutsideTransactionFails() {
        long branch = branch("DRAFT");

        assertThatThrownBy(() -> branchRepository.lockStatusForBooking(branch))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void readOnlyTransactionFails() {
        long branch = branch("DRAFT");
        TransactionTemplate readOnly = tx();
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> branchApi.lockForBooking(branch)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
    }

    @Test
    void returnsStatusReadUnderLockAndEmptyForUnknownId() {
        long draft = branch("DRAFT");
        long active = branch("ACTIVE");

        Optional<BranchStatus> lockedDraft = tx().execute(status -> branchApi.lockForBooking(draft));
        Optional<BranchStatus> lockedActive = tx().execute(status -> branchApi.lockForBooking(active));
        Optional<BranchStatus> unknown = tx().execute(status -> branchApi.lockForBooking(Long.MAX_VALUE));

        assertThat(lockedDraft).contains(BranchStatus.DRAFT);
        assertThat(lockedActive).contains(BranchStatus.ACTIVE);
        assertThat(unknown).isEmpty();
    }

    /** R2/R3 qua service thật: kích hoạt (khóa {@code branches} trước khi kiểm) chờ tới khi lượt đặt commit. */
    @Test
    void branchWriteWaitsUntilBookingCommits() throws Exception {
        long branch = branch("DRAFT");

        Throwable activation = whileBookingLocked(branch, true, () -> branchService.activateBranch(branch));

        assertThat(activation).as("kích hoạt chạy sau khi khóa được nhả, rồi mới kiểm BR-QT-04")
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-QT-04");
        assertNotLocked(branch);
    }

    @Test
    void branchWriteWaitsUntilBookingRollsBack() throws Exception {
        long branch = branch("DRAFT");

        Throwable activation = whileBookingLocked(branch, false, () -> branchService.activateBranch(branch));

        assertThat(activation).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-QT-04");
        assertNotLocked(branch);
    }

    /**
     * Khóa mà mọi luồng ghi lịch / quota / dịch vụ của branch lấy đầu tiên ({@code findByIdForUpdate}:
     * {@code HolidayService}, {@code OpeningHoursService}, {@code QuotaService}, {@code BranchServiceAvailabilityService},
     * {@code BranchService}) phải chờ lượt đặt.
     */
    @Test
    void branchScheduleLockWaitsForBooking() throws Exception {
        long branch = branch("ACTIVE");

        Throwable result = whileBookingLocked(branch, true, () -> tx().executeWithoutResult(status ->
                branchRepository.findByIdForUpdate(branch).orElseThrow()));

        assertThat(result).isNull();
    }

    /** R1: hai lượt đặt cùng chi nhánh xếp hàng. */
    @Test
    void secondBookingOfSameBranchWaits() throws Exception {
        long branch = branch("ACTIVE");

        Throwable result = whileBookingLocked(branch, true, () -> tx().executeWithoutResult(status ->
                assertThat(branchApi.lockForBooking(branch)).contains(BranchStatus.ACTIVE)));

        assertThat(result).isNull();
    }

    @Test
    void bookingOfOtherBranchIsNotBlocked() throws Exception {
        long branch = branch("ACTIVE");
        long other = branch("ACTIVE");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                branchApi.lockForBooking(branch);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Optional<BranchStatus>> contender = pool.submit(() ->
                    tx().execute(status -> branchApi.lockForBooking(other)));
            assertThat(contender.get(10, TimeUnit.SECONDS)).contains(BranchStatus.ACTIVE);
            assertThat(holder.isDone()).isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Câu khóa trả giá trị vô hướng: commit không sinh {@code UPDATE branches}. */
    @Test
    void noUpdateOnCommit() {
        long branch = branch("ACTIVE");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT updated_at, xmin::text AS xmin FROM branches WHERE id = ?", branch);

        tx().executeWithoutResult(status -> branchApi.lockForBooking(branch));

        assertThat(jdbc.queryForMap("SELECT updated_at, xmin::text AS xmin FROM branches WHERE id = ?", branch))
                .isEqualTo(before);
    }

    /** Bẫy persistence context: entity cũ đã nạp vẫn còn, câu native đọc lại DB. */
    @Test
    void lockReturnsFreshStatusAfterStaleRead() {
        long branch = branch("DRAFT");

        AtomicReference<Optional<BranchStatus>> viaApi = new AtomicReference<>();
        AtomicReference<Object> viaEntity = new AtomicReference<>();
        tx().executeWithoutResult(status -> {
            assertThat(branchQuery.findBranch(branch)).get()
                    .extracting(BranchQueryApi.BranchSummary::status).isEqualTo(BranchStatus.DRAFT);

            runInOtherTransaction(() -> jdbc.update(
                    "UPDATE branches SET status = 'ACTIVE', activated_at = now(), updated_at = now() WHERE id = ?",
                    branch));

            viaEntity.set(branchRepository.findById(branch).map(Branch::getStatus).orElseThrow());
            viaApi.set(branchApi.lockForBooking(branch));
        });

        assertThat(viaEntity.get()).hasToString("DRAFT");
        assertThat(viaApi.get()).contains(BranchStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- hỗ trợ

    /**
     * Transaction A khóa chi nhánh qua {@code lockForBooking} rồi giữ; {@code contender} chạy ở thread khác, phải đứng
     * chờ khóa trước khi A commit ({@code commit = true}) hoặc rollback. Trả lỗi mà {@code contender} ném (hoặc null).
     */
    private Throwable whileBookingLocked(long branchId, boolean commit, Runnable contender) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                branchApi.lockForBooking(branchId);
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

    /** Sau khi transaction kết thúc, dòng chi nhánh khóa được ngay ({@code NOWAIT} không lỗi). */
    private void assertNotLocked(long branchId) {
        tx().executeWithoutResult(status -> assertThat(jdbc.queryForObject(
                "SELECT id FROM branches WHERE id = ? FOR UPDATE NOWAIT", Long.class, branchId)).isEqualTo(branchId));
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
                throw new AssertionError("phải có câu đứng chờ khóa branches");
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
                """, Long.class, "BranchBookingLockIT " + UUID.randomUUID(), status, status);
    }
}
