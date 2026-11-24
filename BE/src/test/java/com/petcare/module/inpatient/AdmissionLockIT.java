package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Tranh chấp của nhập viện (docs/adr/0149 mục 16, 18) trên Postgres thật:
 * <ul>
 * <li>hai VET nhập viện cùng một thú cùng lúc → đúng một 201, một 409, một ca, không Visit điều trị thừa;</li>
 * <li>đường (b) lấy khóa xung đột với {@code FOR NO KEY UPDATE} trên đặt chỗ (khóa của giao thú / thú mất): phải chờ, và
 * sau khi bên giữ khóa commit thì đọc lại trạng thái mới (đặt chỗ đã {@code CHECKED_OUT} → 400);</li>
 * <li>đường (a) lấy khóa trên dòng {@code visits} (khóa của hoàn tất lượt): phải chờ, rồi thấy lượt đã
 * {@code COMPLETED} → 400; ngược lại, hoàn tất lượt sau khi nhập viện commit → 409 (guard Visit#5).</li>
 * </ul>
 * Bên giữ khóa là một transaction JDBC ở luồng riêng. Era <b>năm 2097</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionLockIT.TestBeans.class})
class AdmissionLockIT extends AdmissionItBase {

    private static final Instant NOW = Instant.parse("2097-03-14T02:00:00Z");
    /** Đủ lâu để chắc request đang chờ khóa, không phải chưa tới. */
    private static final long BLOCKED_MS = 1500;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private long branch;
    private long vet;
    private long customer;
    private long service;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = enabledInpatientService(branch, 200_000L);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        finish();
    }

    @Test
    void twoVetsAdmittingTheSamePetAtOnceGiveExactlyOneAdmission() throws Exception {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        String first = token(vet);
        String second = token(data.staff("VET", branch));
        CountDownLatch go = new CountDownLatch(1);

        CompletableFuture<ResponseEntity<Map<String, Object>>> a = CompletableFuture.supplyAsync(() -> {
            await(go);
            return admit(first, fromBooking(booking, service));
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> b = CompletableFuture.supplyAsync(() -> {
            await(go);
            return admit(second, fromBooking(booking, service));
        }, pool);
        go.countDown();

        List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                b.get(30, TimeUnit.SECONDS).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        ResponseEntity<Map<String, Object>> loser = statuses.get(0) == 409 ? a.get() : b.get();
        assertThat(errorCode(loser)).isIn("INVALID_STATE_TRANSITION", "CONCURRENCY_CONFLICT");
        assertThat(admissionCount(pet)).isEqualTo(1);
        assertThat(visitCount(pet)).as("Visit điều trị của bên thua đã rollback").isEqualTo(1);
        assertThat(orderCount(pet)).isEqualTo(1);
    }

    @Test
    void fromABookingWaitsForTheHandOverLockAndThenSeesTheCheckedOutStay() throws Exception {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        String token = token(vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM boarding_bookings WHERE id = ? FOR NO KEY UPDATE", Long.class, booking);
            locked.countDown();
            await(release);
            jdbc.update("UPDATE boarding_bookings SET status = 'CHECKED_OUT', end_reason = 'RETURNED' WHERE id = ?",
                    booking);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> admission =
                CompletableFuture.supplyAsync(() -> admit(token, fromBooking(booking, service)), pool);
        assertBlocked(admission);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertRule(admission.get(30, TimeUnit.SECONDS), "BR-LT-13");
        assertThat(admissionCount(pet)).isZero();
        assertThat(visitCount(pet)).as("không Visit điều trị").isZero();
    }

    @Test
    void fromABookingSucceedsOnceTheLockHolderCommitsWithoutChange() throws Exception {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        String token = token(vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM boarding_bookings WHERE id = ? FOR NO KEY UPDATE", Long.class, booking);
            locked.countDown();
            await(release);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> admission =
                CompletableFuture.supplyAsync(() -> admit(token, fromBooking(booking, service)), pool);
        assertBlocked(admission);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(admission.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void fromAVisitWaitsForTheVisitLockAndThenSeesTheCompletedVisit() throws Exception {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        String token = token(vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", Long.class, visit);
            locked.countDown();
            await(release);
            jdbc.update("UPDATE visits SET status = 'COMPLETED', completed_at = now() WHERE id = ?", visit);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> admission =
                CompletableFuture.supplyAsync(() -> admit(token, fromVisit(visit, service, kennel)), pool);
        assertBlocked(admission);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertRule(admission.get(30, TimeUnit.SECONDS), "BR-LT-13");
        assertThat(admissionCount(pet)).isZero();
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void completingTheVisitAfterTheAdmissionCommittedIs409AndLeavesTheVisitInProgress() {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        String token = token(vet);
        assertThat(admit(token, fromVisit(visit, service, kennel)).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map<String, Object>> complete = call(org.springframework.http.HttpMethod.POST,
                "/api/visits/" + visit + "/complete", token, null);

        assertStatus(complete, 409, "INVALID_STATE_TRANSITION");
        assertThat(message(complete)).contains("còn ca nội trú");
        assertThat(jdbc.queryForObject("SELECT status FROM visits WHERE id = ?", String.class, visit))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, visit))
                .isEqualTo("OPEN");
    }

    @Test
    void aTreatmentVisitWithOnlyTheInpatientLineCompletesAfterDischargeWithoutDiagnosis() {
        long pet = data.pet(customer);
        long booking = data.checkedInBooking(branch, customer, pet, vet);
        String token = token(vet);
        Map<String, Object> admitted = dataOf(admit(token, fromBooking(booking, service)));
        long treatment = idOf(admitted, "visitId");
        // Mô phỏng kết quả của xuất viện bằng SQL (cách chạy thật qua API: AdmissionDischargeIT): dòng INPATIENT + ca DISCHARGED.
        long line = data.orderLine(treatment, service, 1, 200_000L, vet);
        jdbc.update("""
                UPDATE admissions SET status = 'DISCHARGED', discharge_outcome = 'HOME', discharge_summary = 'Ổn',
                    discharged_by = ?, discharged_at = now(), order_line_id = ? WHERE id = ?""", vet, line,
                idOf(admitted, "admissionId"));

        ResponseEntity<Map<String, Object>> complete = call(org.springframework.http.HttpMethod.POST,
                "/api/visits/" + treatment + "/complete", token, null);

        assertThat(complete.getStatusCode().value()).as(String.valueOf(complete.getBody())).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, treatment))
                .isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- xuất viện (N4, docs/adr/0149 mục 22)

    /** Hai lần bấm "xuất viện" cùng lúc: khóa dòng ca; bên sau thấy {@code DISCHARGED} → 409, tiền chỉ tính một lần. */
    @Test
    void twoDischargesOfTheSameAdmissionAtOnceBillExactlyOnce() throws Exception {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        String token = token(vet);
        long admission = idOf(dataOf(admit(token, fromVisit(visit, service, kennel))), "admissionId");
        CountDownLatch go = new CountDownLatch(1);

        CompletableFuture<ResponseEntity<Map<String, Object>>> a = CompletableFuture.supplyAsync(() -> {
            await(go);
            return discharge(admission, token);
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> b = CompletableFuture.supplyAsync(() -> {
            await(go);
            return discharge(admission, token);
        }, pool);
        go.countDown();

        List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                b.get(30, TimeUnit.SECONDS).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ResponseEntity<Map<String, Object>> loser = statuses.get(0) == 409 ? a.get() : b.get();
        assertThat(errorCode(loser)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(dischargeLines(visit)).as("đúng một dòng nội trú").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE visit_id = ?", Long.class, visit))
                .isEqualTo(200_000L);
    }

    /**
     * Xuất viện phải chờ khóa dòng ca (người khác đang giữ), rồi đọc lại: thấy ca đã {@code DISCHARGED} → 409 và không
     * thêm dòng thứ hai. Không có khóa (đọc thường) thì request không chờ và tính tiền hai lần.
     */
    @Test
    void dischargeWaitsForTheAdmissionLockAndThenSeesTheDischargedAdmission() throws Exception {
        long pet = data.pet(customer);
        long visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        String token = token(vet);
        long admission = idOf(dataOf(admit(token, fromVisit(visit, service, kennel))), "admissionId");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admissions WHERE id = ? FOR NO KEY UPDATE", Long.class, admission);
            locked.countDown();
            await(release);
            // kết quả của một lần xuất viện khác đã commit trong lúc request chờ
            long line = data.orderLine(visit, service, 1, 200_000L, vet);
            jdbc.update("""
                    UPDATE admissions SET status = 'DISCHARGED', discharge_outcome = 'HOME', discharge_summary = 'Ổn',
                        discharged_by = ?, discharged_at = now(), order_line_id = ? WHERE id = ?""", vet, line,
                    admission);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> discharge(admission, token), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertStatus(request.get(30, TimeUnit.SECONDS), 409, "INVALID_STATE_TRANSITION");
        assertThat(dischargeLines(visit)).as("chỉ dòng của bên giữ khóa").isEqualTo(1);
    }

    private ResponseEntity<Map<String, Object>> discharge(long admissionId, String bearer) {
        return call(org.springframework.http.HttpMethod.POST, ADMISSIONS + "/" + admissionId + "/discharge", bearer,
                "{'outcome':'HOME','summary':'Ổn'}");
    }

    private int dischargeLines(long visit) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""",
                Integer.class, visit);
    }

    private static void assertBlocked(CompletableFuture<?> request) throws Exception {
        try {
            request.get(BLOCKED_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("request phải chờ khóa nhưng đã xong: " + request.get());
        } catch (TimeoutException expected) {
            // đang chờ khóa
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
