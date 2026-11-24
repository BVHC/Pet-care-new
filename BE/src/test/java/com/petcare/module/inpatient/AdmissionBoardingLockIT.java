package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;

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
 * Khóa của guard BR-LT-17 (docs/adr/0149 mục 37, 41): nhập viện từ lưu trú giữ {@code boarding_bookings FOR SHARE} tới
 * khi commit; bắt đầu trả thú / giao thú / thú mất lấy {@code FOR NO KEY UPDATE} cùng dòng nên phải chờ, rồi đọc
 * {@code AdmissionQueryApi} <b>sau</b> khóa và thấy ca vừa commit → 409. Nếu bên giữ khóa rollback thì request đi tiếp.
 * Bên giữ khóa là một transaction JDBC ở luồng riêng, chèn thẳng dòng {@code admissions}. Era <b>năm 2111</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionBoardingLockIT.TestBeans.class})
class AdmissionBoardingLockIT extends AdmissionBoardingItBase {

    /** 09:00 ngày 14/3/2111 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2111-03-14T02:00:00Z");
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

    @BeforeEach
    void setUp() {
        setUpBoarding(NOW);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        finish();
    }

    @Test
    void startCheckoutWaitsForTheAdmissionToCommitThenIsRefused() throws Exception {
        assertWaitsThenRefused(this::startCheckout);
    }

    @Test
    void handOverWaitsForTheAdmissionToCommitThenIsRefusedBeforeThePaymentCheck() throws Exception {
        assertWaitsThenRefused(this::handOver);
    }

    @Test
    void endDeceasedWaitsForTheAdmissionToCommitThenIsRefusedAndOpensNoOrder() throws Exception {
        assertWaitsThenRefused(this::endDeceased);
    }

    @Test
    void startCheckoutProceedsWhenTheAdmissionRollsBack() throws Exception {
        long pet = data.pet(customer);
        long booking = stay(pet);
        long visit = data.visit(branch, customer, pet, vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = holder(booking, pet, visit, locked, release, true);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> startCheckout(booking), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        ResponseEntity<Map<String, Object>> response = request.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        assertThat(admissionCount(pet)).isZero();
        assertThat(boardingOrders(booking)).isEqualTo(1);
    }

    /** Giữ khóa đặt chỗ + chèn ca, request chờ, commit → request đọc thấy ca và bị từ chối, đặt chỗ không đổi. */
    private void assertWaitsThenRefused(LongFunction<ResponseEntity<Map<String, Object>>> action) throws Exception {
        long pet = data.pet(customer);
        long booking = stay(pet);
        long kennel = bookingKennel(booking);
        long visit = data.visit(branch, customer, pet, vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> holder = holder(booking, pet, visit, locked, release, false);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> action.apply(booking), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertStayBlocked(request.get(30, TimeUnit.SECONDS));
        assertThat(admissionCount(pet)).isEqualTo(1);
        assertThat(bookingStatus(booking)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        assertThat(boardingOrders(booking)).isZero();
    }

    /**
     * Một "nhập viện từ lưu trú đang chạy": khóa đặt chỗ {@code FOR SHARE}, chèn ca {@code ADMITTED}, giữ tới khi
     * {@code release}; rồi commit, hoặc rollback khi {@code rollback}.
     */
    private CompletableFuture<Void> holder(long booking, long pet, long visit, CountDownLatch locked,
            CountDownLatch release, boolean rollback) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM boarding_bookings WHERE id = ? FOR SHARE", Long.class, booking);
            jdbc.update("""
                    INSERT INTO admissions (code, visit_id, pet_id, branch_id, boarding_booking_id, service_id, unit_price,
                            status, admitted_by, admitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ADMITTED', ?, now())""",
                    "NT-IT-" + UUID.randomUUID().toString().substring(0, 8), visit, pet, branch, booking, service, PRICE,
                    vet);
            locked.countDown();
            await(release);
            if (rollback) {
                status.setRollbackOnly();
            }
        }), pool);
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
