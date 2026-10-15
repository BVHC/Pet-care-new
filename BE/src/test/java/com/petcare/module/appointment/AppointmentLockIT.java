package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.service.AppointmentLocks;
import com.petcare.module.branch.api.BranchApi;

/**
 * Khóa thú của lịch hẹn trên PostgreSQL 17 (docs/adr/0041, BR-LH-05): advisory theo transaction — cùng thú thì xếp
 * hàng, khác thú thì không; nhả khi commit <b>và</b> rollback (không kẹt trên connection của pool); chỉ chạy trong
 * transaction ghi của bên gọi; lỗi khóa đánh dấu bên gọi rollback-only; deadlock giữa khóa thú và khóa chi nhánh ra
 * {@code PessimisticLockingFailureException} → 409 {@code CONCURRENCY_CONFLICT} ({@code GlobalExceptionHandler}).
 * Mỗi test dùng id thú riêng, nên khóa của test này không chạm test khác.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentLockIT {

    private static final String WAITING_ON_ADVISORY = """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'advisory'""";

    private static final String HELD_PET_LOCKS = """
            SELECT count(*) FROM pg_locks
            WHERE locktype = 'advisory' AND classid = ? AND objid = ? AND objsubid = 2""";

    /** Id thú giả, mỗi test một số khác nhau: advisory lock không cần dòng {@code pets} tồn tại. */
    private static final AtomicLong NEXT_PET = new AtomicLong(900_000_000L + (System.nanoTime() % 1_000_000L));

    @Autowired
    private AppointmentLocks locks;

    @Autowired
    private AppointmentRepository repository;

    @Autowired
    private BranchApi branchApi;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static long newPet() {
        return NEXT_PET.incrementAndGet();
    }

    @Test
    void outsideTransactionFails() {
        assertThatThrownBy(() -> locks.lockPet(newPet())).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** Gọi thẳng repository ngoài transaction thì lỗi, không âm thầm khóa rồi nhả ngay trong autocommit. */
    @Test
    void repositoryLockOutsideTransactionFails() {
        assertThatThrownBy(() -> repository.acquireTransactionLock(AppointmentLocks.PET_LOCK_NAMESPACE, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void readOnlyTransactionFails() {
        TransactionTemplate readOnly = tx();
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> locks.lockPet(newPet())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
    }

    @Test
    void samePetWaitsUntilHolderCommits() throws Exception {
        long pet = newPet();

        whilePetLocked(pet, true, () -> tx().executeWithoutResult(s -> locks.lockPet(pet)));

        assertThat(heldPetLocks(pet)).isZero();
    }

    @Test
    void samePetWaitsUntilHolderRollsBack() throws Exception {
        long pet = newPet();

        whilePetLocked(pet, false, () -> tx().executeWithoutResult(s -> locks.lockPet(pet)));

        assertThat(heldPetLocks(pet)).isZero();
    }

    @Test
    void otherPetIsNotBlocked() throws Exception {
        long pet = newPet();
        long other = newPet();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                locks.lockPet(pet);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> contender = pool.submit(() -> tx().executeWithoutResult(status -> locks.lockPet(other)));
            contender.get(10, TimeUnit.SECONDS);
            assertThat(holder.isDone()).as("khóa thú khác xong trong khi thú đầu vẫn bị giữ").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Khóa nằm trong {@code pg_locks} khi transaction còn mở, biến mất ngay khi transaction kết thúc. */
    @Test
    void lockLivesExactlyAsLongAsTheTransaction() {
        long pet = newPet();

        tx().executeWithoutResult(status -> {
            locks.lockPet(pet);
            assertThat(jdbc.queryForObject(HELD_PET_LOCKS, Long.class, AppointmentLocks.PET_LOCK_NAMESPACE,
                    (int) pet)).isEqualTo(1L);
        });
        assertThat(heldPetLocks(pet)).isZero();

        tx().executeWithoutResult(status -> {
            locks.lockPet(pet);
            status.setRollbackOnly();
        });
        assertThat(heldPetLocks(pet)).isZero();
    }

    /**
     * Lỗi khóa đi qua proxy {@code MANDATORY} đánh dấu transaction bên gọi rollback-only: bắt lỗi rồi commit thì nhận
     * {@code UnexpectedRollbackException}. Ghi lại bẫy để phần 2–4 không bắt lỗi khóa (khuôn {@code BranchStaffLockIT}).
     */
    @Test
    void lockFailureMarksCallerRollbackOnly() throws Exception {
        long pet = newPet();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                locks.lockPet(pet);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                try {
                    locks.lockPet(pet);
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

    /**
     * Deadlock thật (thú → chi nhánh ∥ chi nhánh → thú — thứ tự sai mà ADR-0041 cấm): PostgreSQL hủy đúng một bên với
     * 40P01, bên đó nhận {@code PessimisticLockingFailureException} (handler → 409), bên kia commit được.
     */
    @Test
    void deadlockSurfacesAsPessimisticLockingFailure() throws Exception {
        long pet = newPet();
        long branch = data.branch("DRAFT");
        CountDownLatch aHolds = new CountDownLatch(1);
        CountDownLatch bHolds = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = pool.submit(() -> attempt(() -> tx().executeWithoutResult(status -> {
                locks.lockPet(pet);
                aHolds.countDown();
                await(bHolds);
                branchApi.lockForBooking(branch);
            })));
            Future<Throwable> b = pool.submit(() -> attempt(() -> tx().executeWithoutResult(status -> {
                branchApi.lockForBooking(branch);
                bHolds.countDown();
                await(aHolds);
                locks.lockPet(pet);
            })));

            List<Throwable> outcomes = new ArrayList<>();
            outcomes.add(a.get(60, TimeUnit.SECONDS));
            outcomes.add(b.get(60, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(t -> t == null).hasSize(1);
            assertThat(outcomes).filteredOn(t -> t != null).singleElement()
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            aHolds.countDown();
            bHolds.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------------------------------------------------------------- hỗ trợ

    /**
     * Transaction giữ khóa thú; {@code contender} chạy ở thread khác, phải đứng chờ advisory ({@code pg_stat_activity})
     * và chưa xong khi bên giữ còn mở; sau khi bên giữ commit hoặc rollback thì {@code contender} xong không lỗi.
     */
    private void whilePetLocked(long pet, boolean commit, Runnable contender) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx().executeWithoutResult(status -> {
                locks.lockPet(pet);
                held.countDown();
                await(release);
                if (!commit) {
                    status.setRollbackOnly();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> call = pool.submit(contender);
            awaitWaitingOnAdvisory();
            assertThat(call.isDone()).as("phải chờ khóa thú").isFalse();
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            call.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long heldPetLocks(long pet) {
        return jdbc.queryForObject(HELD_PET_LOCKS, Long.class, AppointmentLocks.PET_LOCK_NAMESPACE, (int) pet);
    }

    private void awaitWaitingOnAdvisory() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_ADVISORY, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("phải có câu đứng chờ advisory lock");
            }
            Thread.sleep(20);
        }
    }

    private static Throwable attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
