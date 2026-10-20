package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.service.AppointmentNoShowService;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.appointment.service.NoShowCutoff;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * ST05 {@code NO_SHOW} (T25 phần 2, docs/adr/0048) trên Postgres 17 thật. Đồng hồ {@link MutableClock} ở <b>năm 2000</b>
 * và mọi lịch của test này ở tháng 5/2000, nên job (quét theo cutoff 2000) không bao giờ đụng lịch của IT khác. Job chạy
 * qua {@code run()} thật; {@code BoardingQueryApi} là {@code @MockitoBean} (còn placeholder — D033).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NoShowIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class NoShowIT {

    private static final LocalDate DAY = LocalDate.of(2000, 6, 1);
    private static final Instant T0 = vn(DAY, LocalTime.of(10, 30));
    private static final String NO_SHOW_KEY = "appointment.no_show_after_minutes";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @MockitoBean
    private BoardingQueryApi boardings;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private NoShowJob job;
    @Autowired
    private AppointmentNoShowService service;
    @Autowired
    private BookingRestrictionApi restrictions;
    @Autowired
    private ConfigurableListableBeanFactory beans;
    @Autowired
    private SystemConfigService configs;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long staff;
    private long customer;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        clock.set(T0);
        reset(boardings);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        staff = data.receptionist();
        customer = data.counterCustomer();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_noshow_it_update ON appointments");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_noshow_it_restriction ON booking_restrictions");
        jdbc.execute("DROP FUNCTION IF EXISTS noshow_it_fail()");
        data.cleanUp();
    }

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private long booked(long ownerCustomer, LocalDate date, LocalTime start, String status) {
        long pet = data.pet(ownerCustomer);
        return data.appointment(ownerCustomer, pet, branch, medical, "MEDICAL", date, start, status, staff);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private int restrictionCount(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Integer.class,
                customerId);
    }

    private NoShowCutoff cutoff() {
        return service.cutoff(clock.instant());
    }

    /** Toàn bộ cột trừ {@code status}, {@code updated_at}. */
    private String rowWithoutStatus(long id) {
        return jdbc.queryForObject("SELECT (to_jsonb(a) - 'status' - 'updated_at')::text FROM appointments a WHERE id = ?",
                String.class, id);
    }

    // --- nghiệp vụ -----------------------------------------------------------------------------------------------

    @Test
    void overdueBookedBecomesNoShowAndOnlyStatusChanges() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        String before = rowWithoutStatus(id);

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(rowWithoutStatus(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT late_cancel FROM appointments WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(restrictionCount(customer)).isZero();
    }

    @Test
    void notOverdueAndOtherStatusesAreUntouched() {
        long future = booked(customer, LocalDate.of(2000, 6, 1), LocalTime.of(11, 0), "BOOKED");
        long checkedIn = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "CHECKED_IN");
        long cancelled = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "COMPLETED");

        job.run();

        assertThat(status(future)).isEqualTo("BOOKED");
        assertThat(status(checkedIn)).isEqualTo("CHECKED_IN");
        assertThat(status(cancelled)).isEqualTo("COMPLETED");
    }

    @Test
    void boundaryExactlyNoShowAfterStaysBookedOneMicrosecondLaterIsNoShow() {
        long id = booked(customer, DAY, LocalTime.of(9, 0), "BOOKED");

        clock.set(vn(DAY, LocalTime.of(9, 30)));
        job.run();
        assertThat(status(id)).isEqualTo("BOOKED");

        clock.set(vn(DAY, LocalTime.of(9, 30)).plusNanos(1_000));
        job.run();
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void thirdViolationCreatesExactlyOneRestriction() {
        long a = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");
        long later = booked(customer, LocalDate.of(2000, 6, 5), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(List.of(status(a), status(b), status(c))).containsOnly("NO_SHOW");
        assertThat(status(later)).as("lịch đã đặt trước đó giữ nguyên").isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT violation_count FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customer)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT lifted_at IS NULL FROM booking_restrictions WHERE customer_id = ?",
                Boolean.class, customer)).isTrue();
        Boolean restricted = tx.execute(s -> restrictions.isRestricted(customer, clock.instant()));
        assertThat(restricted).isTrue();
    }

    @Test
    void lockedAccountCustomerStillCounted() {
        long account = data.customerAccount("noshow-" + System.nanoTime() + "@example.com", "ACTIVE");
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'test' WHERE id = ?", account);
        long online = data.onlineCustomer(account, "Khách bị khóa");
        long a = booked(online, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(online, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(online, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(List.of(status(a), status(b), status(c))).containsOnly("NO_SHOW");
        assertThat(restrictionCount(online)).isEqualTo(1);
    }

    @Test
    void freesPetLimitAndQuota() {
        long pet = data.pet(customer);
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2000, 5, 20),
                LocalTime.of(9, 0), "BOOKED", staff);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Integer.class, pet);

        job.run();

        assertThat(before).isEqualTo(1);
        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Integer.class, pet)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE id = ? AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')""",
                Integer.class, id)).as("không còn chiếm quota").isZero();
    }

    // --- commit / rollback -----------------------------------------------------------------------------------------

    @Test
    void evaluationFailureRollsBackNoShowAndKeepsExceptionType(CapturedOutput output) {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long updatedBefore = jdbc.queryForObject("SELECT extract(epoch FROM updated_at)::bigint FROM appointments WHERE id = ?",
                Long.class, id);
        doThrow(new UnsupportedOperationException("D033")).when(boardings).countViolations(anyLong(), any());

        assertThatThrownBy(() -> service.markNoShow(id, cutoff())).isInstanceOf(UnsupportedOperationException.class);
        job.run();

        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM updated_at)::bigint FROM appointments WHERE id = ?",
                Long.class, id)).isEqualTo(updatedBefore);
        assertThat(output).contains("NO_SHOW_FAILED appointmentId=" + id);
        assertAdvisoryLocksReleased();
    }

    @Test
    void failedAppointmentDoesNotBlockOthers() {
        long failing = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long otherCustomer = data.counterCustomer();
        long fine = booked(otherCustomer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        doThrow(new UnsupportedOperationException("D033")).when(boardings).countViolations(
                org.mockito.ArgumentMatchers.eq(customer), any());

        job.run();

        assertThat(status(failing)).isEqualTo("BOOKED");
        assertThat(status(fine)).isEqualTo("NO_SHOW");
    }

    @Test
    void updateFailureLeavesBooked() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        failingTrigger("trg_noshow_it_update", "appointments", "UPDATE", "NEW.customer_id = " + customer);

        job.run();

        assertThat(status(id)).isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
    }

    /**
     * {@code saveAndFlush} trước {@code evaluateAfterViolation}: lỗi UPDATE lộ ra ngay ở bước ghi, trước khi lấy advisory
     * 1002. Với {@code save}, Hibernate chỉ flush khi câu native đầu tiên trong {@code evaluateAfterViolation} chạy, nên
     * lỗi nằm trong khung của nó (cùng kết quả DB, khác thứ tự — mutation {@code save} làm đỏ test này).
     */
    @Test
    void updateFailureSurfacesBeforeTheEvaluation() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        failingTrigger("trg_noshow_it_update", "appointments", "UPDATE", "NEW.customer_id = " + customer);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.markNoShow(id, cutoff()));

        assertThat(thrown).isNotNull();
        assertThat(stackTraceOf(thrown)).contains("noshow_it: UPDATE bị chặn")
                .doesNotContain("BookingRestrictionService.evaluateAfterViolation");
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    @Test
    void restrictionInsertFailureRollsBackTheFlushedNoShow() {
        long a = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long b = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long c = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");
        failingTrigger("trg_noshow_it_restriction", "booking_restrictions", "INSERT", "NEW.customer_id = " + customer);

        job.run();

        assertThat(status(a)).isEqualTo("NO_SHOW");
        assertThat(status(b)).isEqualTo("NO_SHOW");
        assertThat(status(c)).as("lịch thứ 3 gây hạn chế: INSERT lỗi nên rollback cả NO_SHOW").isEqualTo("BOOKED");
        assertThat(restrictionCount(customer)).isZero();
    }

    // --- race ------------------------------------------------------------------------------------------------------

    @Test
    void rowHeldByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
            held.countDown();
            await(release);
            jdbc.update("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?", id);
        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            NoShowCutoff cutoff = cutoff();

            // Có timeout: bỏ SKIP LOCKED thì markNoShow đứng chờ dòng, test phải đỏ ngay chứ không treo tới latch.
            NoShowResult result = CompletableFuture.supplyAsync(() -> service.markNoShow(id, cutoff))
                    .get(5, TimeUnit.SECONDS);

            assertThat(result).isEqualTo(NoShowResult.SKIPPED);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        job.run();
        assertThat(status(id)).as("check-in thắng, lượt sau bỏ qua").isEqualTo("CHECKED_IN");
    }

    @Test
    void twoConcurrentMarksOfSameAppointmentMarkOnce() throws Exception {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        NoShowCutoff cutoff = cutoff();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<NoShowResult> first = pool.submit(() -> {
                await(start);
                return service.markNoShow(id, cutoff);
            });
            Future<NoShowResult> second = pool.submit(() -> {
                await(start);
                return service.markNoShow(id, cutoff);
            });
            start.countDown();
            List<NoShowResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(NoShowResult.MARKED, NoShowResult.SKIPPED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void rowThatLeftBookedIsSkippedNotAnError() {
        long checkedIn = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "CHECKED_IN");
        long noShow = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "NO_SHOW");
        long cancelled = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "CANCELLED");
        jdbc.update("UPDATE appointments SET cancel_source = 'CUSTOMER', cancelled_at = now() WHERE id = ?", cancelled);
        NoShowCutoff cutoff = cutoff();

        assertThat(service.markNoShow(checkedIn, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(service.markNoShow(noShow, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(service.markNoShow(cancelled, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(status(checkedIn)).isEqualTo("CHECKED_IN");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
    }

    @Test
    void slotAtExactlyTheCutoffIsNotSelectedByTheQueries() {
        long id = booked(customer, DAY, LocalTime.of(9, 0), "BOOKED");
        clock.set(vn(DAY, LocalTime.of(9, 30)));
        NoShowCutoff cutoff = cutoff();

        assertThat(service.findOverdueIds(cutoff, 0, 100)).doesNotContain(id);
        assertThat(service.markNoShow(id, cutoff)).isEqualTo(NoShowResult.SKIPPED);
        assertThat(status(id)).isEqualTo("BOOKED");
    }

    /** Keyset theo khung giờ rồi id: mỗi lịch đúng một lần qua các lô, kể cả khi thứ tự id ngược thứ tự khung giờ. */
    @Test
    void batchesWalkInSlotOrderAndVisitEachAppointmentOnce() {
        long late = booked(customer, LocalDate.of(2000, 5, 22), LocalTime.of(9, 0), "BOOKED");
        long sameSlotA = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long sameSlotB = booked(customer, LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        long early = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(14, 30), "BOOKED");
        NoShowCutoff cutoff = cutoff();
        List<Long> mine = List.of(late, sameSlotA, sameSlotB, early);

        List<Long> walked = new java.util.ArrayList<>();
        long after = 0;
        for (List<Long> batch = service.findOverdueIds(cutoff, after, 1); !batch.isEmpty();
                batch = service.findOverdueIds(cutoff, after, 1)) {
            walked.addAll(batch);
            after = batch.get(0);
        }

        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked.stream().filter(mine::contains).toList())
                .containsExactly(early, sameSlotA, sameSlotB, late);
    }

    @Test
    void rerunChangesNothing() {
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        job.run();
        String after = rowWithoutStatus(id);

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
        assertThat(rowWithoutStatus(id)).isEqualTo(after);
    }

    /**
     * [CFG] đọc lúc chạy (docs/adr/0048 §2, D036 — đã xác nhận docs/adr/0052): giá trị mới áp từ lượt kế tiếp, kể cả
     * cho lịch đã đặt.
     */
    @Test
    void configChangeAppliesFromNextRun() {
        long id = booked(customer, DAY, LocalTime.of(9, 45), "BOOKED");
        String original = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = ?", String.class, NO_SHOW_KEY);
        try {
            jdbc.update("UPDATE system_configs SET value = '60' WHERE key = ?", NO_SHOW_KEY);
            configs.reload();

            job.run();
            assertThat(status(id)).as("10:30 chưa quá 09:45 + 60 phút").isEqualTo("BOOKED");
        } finally {
            jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", original, NO_SHOW_KEY);
            configs.reload();
        }

        job.run();
        assertThat(status(id)).as("10:30 đã quá 09:45 + 30 phút").isEqualTo("NO_SHOW");
    }

    // --- index -----------------------------------------------------------------------------------------------------

    /**
     * Dữ liệu gần thật (20 000 dòng chèn trong transaction rollback + {@code ANALYZE}, khuôn
     * {@code BookAppointmentIT.countQueriesUseTheirIndexesOnRealisticData}): quá khứ ở trạng thái cuối, tương lai
     * {@code BOOKED}, chỉ vài lịch quá hạn — đúng hình dạng khi ST05 chạy đều. Câu quét phải dùng
     * {@code ix_appointments_status_slot}, không duyệt khóa chính theo {@code ORDER BY id}; câu khóa đi theo khóa chính.
     */
    @Test
    void overdueQueriesUseTheirIndexesOnRealisticData() {
        Map<String, String> plans = tx.execute(s -> {
            s.setRollbackOnly();
            jdbc.update("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    SELECT 'NOSHOW-EXPLAIN ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 2000) g""");
            jdbc.update("""
                    INSERT INTO pets (customer_id, name, species, sex)
                    SELECT id, 'NOSHOW-EXPLAIN', 'DOG', 'MALE' FROM customers WHERE full_name LIKE 'NOSHOW-EXPLAIN %'""");
            jdbc.update("""
                    INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                            slot_start, status, channel, booked_by)
                    SELECT 'LH-NSX-' || g, p.customer_id, p.id, ?, ?, 'MEDICAL', DATE '2026-01-01' + (g / 400),
                           TIME '08:00' + (g % 8) * INTERVAL '30 minutes',
                           CASE WHEN g / 400 >= 25 OR g % 1000 = 0 THEN 'BOOKED'
                                ELSE (ARRAY['COMPLETED', 'CANCELLED', 'NO_SHOW'])[1 + g % 3] END, 'COUNTER', ?
                    FROM generate_series(0, 19999) g
                    JOIN (SELECT id, customer_id, row_number() OVER (ORDER BY id) - 1 AS n FROM pets
                          WHERE name = 'NOSHOW-EXPLAIN') p ON p.n = g % 2000""", branch, medical, staff);
            jdbc.execute("ANALYZE appointments");
            // Mốc = đầu ngày 25 (2026-01-26 08:00): chỉ 10 lịch quá khứ còn BOOKED là quá hạn, 10 000 lịch tương lai không.
            LocalDate cutoffDate = LocalDate.of(2026, 1, 26);
            String cutoffTime = "08:00";
            long probe = jdbc.queryForObject("SELECT min(id) FROM appointments WHERE code LIKE 'LH-NSX-%'", Long.class);
            long middle = jdbc.queryForObject("""
                    SELECT id FROM appointments WHERE code LIKE 'LH-NSX-%' AND status = 'BOOKED'
                      AND slot_date < DATE '2026-01-26' ORDER BY slot_date, slot_start, id OFFSET 4 LIMIT 1""", Long.class);
            Map<String, String> result = new HashMap<>();
            result.put("findOverdueBookedIds", plan("findOverdueBookedIds",
                    new Class<?>[] {LocalDate.class, String.class, int.class}, cutoffDate, cutoffTime, 100));
            result.put("findOverdueBookedIdsAfter", plan("findOverdueBookedIdsAfter",
                    new Class<?>[] {LocalDate.class, String.class, long.class, int.class}, cutoffDate, cutoffTime,
                    middle, 100));
            result.put("rows", String.valueOf(jdbc.queryForObject("""
                    SELECT count(*) FROM appointments WHERE code LIKE 'LH-NSX-%' AND status = 'BOOKED'
                      AND (slot_date, slot_start) < (DATE '2026-01-26', TIME '08:00')""", Integer.class)));
            result.put("lockOverdueBooked", plan("lockOverdueBooked",
                    new Class<?>[] {long.class, LocalDate.class, String.class}, probe, cutoffDate, cutoffTime));
            return result;
        });

        assertThat(plans.get("rows")).as("dữ liệu đúng hình dạng: 10 lịch quá hạn").isEqualTo("10");
        assertThat(plans.get("findOverdueBookedIds")).doesNotContain("Seq Scan").doesNotContain("appointments_pkey")
                .contains("Index Scan using ix_appointments_status_slot on appointments");
        assertThat(plans.get("findOverdueBookedIdsAfter")).as("lô sau: bảng ngoài theo index, lịch mốc theo khóa chính")
                .doesNotContain("Seq Scan").contains("Index Scan using ix_appointments_status_slot on appointments a")
                .contains("appointments_pkey on appointments p");
        assertThat(plans.get("lockOverdueBooked")).doesNotContain("Seq Scan").contains("appointments_pkey");
    }

    // --- phạm vi truy cập ------------------------------------------------------------------------------------------

    @Test
    void jobRunsWithoutSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        long id = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");

        job.run();

        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    @Test
    void onlyTheJobDependsOnTheService() {
        String[] dependents = beans.getDependentBeans("appointmentNoShowService");

        assertThat(dependents).filteredOn(name -> !name.contains("NoShowIT")).containsExactly("noShowJob");
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private String plan(String method, Class<?>[] types, Object... args) {
        try {
            String sql = AppointmentRepository.class.getMethod(method, types).getAnnotation(Query.class).value()
                    .replaceAll(":[a-zA-Z]+", "?");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stackTraceOf(Throwable thrown) {
        java.io.StringWriter out = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    private void failingTrigger(String name, String table, String event, String when) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION noshow_it_fail() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'noshow_it: %s bị chặn'; END $$ LANGUAGE plpgsql""".formatted(event));
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table
                + " FOR EACH ROW WHEN (" + when + ") EXECUTE FUNCTION noshow_it_fail()");
    }

    private void assertAdvisoryLocksReleased() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 1002 AND granted""",
                Integer.class)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
