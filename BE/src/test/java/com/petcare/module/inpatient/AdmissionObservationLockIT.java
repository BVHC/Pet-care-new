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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Tranh chấp của ghi theo dõi nội trú (docs/adr/0149 mục 34) trên Postgres thật. Bên giữ khóa là một transaction JDBC ở
 * luồng riêng; request phải <b>chưa xong</b> sau 1,5 s (đang chờ khóa), rồi sau khi bên giữ commit thì <b>đọc lại</b>
 * trạng thái mới. Các test chứng minh:
 * <ul>
 * <li>ghi theo dõi khóa dòng ca <b>trước</b> khi ghi (ràng buộc của ADR-0149 mục 23): chờ xuất viện đang giữ ca, rồi thấy
 * ca {@code DISCHARGED} → 409 và <b>không</b> có mục theo dõi, dòng cân hay outbox nào; xuất viện và ghi theo dõi chạy
 * đồng thời chỉ có hai kết quả hợp lệ;</li>
 * <li>hai người ghi cùng ca xếp hàng ở khóa ca và cùng thành công;</li>
 * <li>cân nặng cần {@code pets FOR SHARE}: chờ một transaction giữ {@code pets FOR NO KEY UPDATE} rồi vẫn thành công, cả
 * ba bản ghi cùng commit (không deadlock: không luồng nào khóa {@code pets} rồi mới khóa {@code admissions}).</li>
 * </ul>
 * Era <b>năm 2107</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionObservationLockIT.TestBeans.class})
class AdmissionObservationLockIT extends AdmissionObservationItBase {

    private static final Instant NOW = Instant.parse("2107-03-14T02:00:00Z");
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

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private long branch;
    private long vet;
    private long customer;
    private long service;
    private String token;
    private Admitted a;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branch = data.branch();
        vet = data.staff("VET", branch);
        long[] online = data.onlineCustomer("Nguyễn Văn An", "ACTIVE");
        customer = online[1];
        service = enabledInpatientService(branch, 200_000L);
        token = token(vet);
        a = admitFromVisit(branch, customer, service, vet, token);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        finish();
    }

    @Test
    void recordingWaitsForTheAdmissionLockAndThenSeesTheDischargedAdmissionWritingNothing() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admissions WHERE id = ? FOR NO KEY UPDATE", Long.class, a.id());
            locked.countDown();
            await(release);
            // kết quả của một lần xuất viện khác đã commit trong lúc request chờ
            long line = data.orderLine(a.visit(), service, 1, 200_000L, vet);
            jdbc.update("""
                    UPDATE admissions SET status = 'DISCHARGED', discharge_outcome = 'HOME', discharge_summary = 'Ổn',
                        discharged_by = ?, discharged_at = now(), order_line_id = ? WHERE id = ?""", vet, line, a.id());
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> record(token, a.id(), body(true, "5")), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertStatus(request.get(30, TimeUnit.SECONDS), 409, "INVALID_STATE_TRANSITION");
        assertThat(footprintOf(a)).as("không mục theo dõi / dòng cân / outbox nào sau xuất viện").isEqualTo("0/0/0");
    }

    @Test
    void recordingProceedsOnceTheLockHolderCommitsWithoutChange() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admissions WHERE id = ? FOR NO KEY UPDATE", Long.class, a.id());
            locked.countDown();
            await(release);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> record(token, a.id(), body(true, "5")), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(request.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("1/1/2");
    }

    /**
     * Xuất viện (khóa ca) chạy đồng thời với ghi theo dõi: chỉ có hai kết quả hợp lệ — ghi trước (201, rồi xuất viện 200,
     * mục theo dõi còn đó) hoặc xuất viện trước (409, không để lại gì). Lặp vài ca để cả hai thứ tự đều có cơ hội xảy ra.
     */
    @Test
    void dischargeAndRecordRunningTogetherOnlyEverProduceTheTwoValidOutcomes() throws Exception {
        int recordedFirst = 0;
        int dischargedFirst = 0;
        for (int i = 0; i < 6; i++) {
            Admitted ca = admitFromVisit(branch, customer, service, vet, token);
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<ResponseEntity<Map<String, Object>>> recording = CompletableFuture.supplyAsync(() -> {
                await(go);
                return record(token, ca.id(), body(true, "5"));
            }, pool);
            CompletableFuture<ResponseEntity<Map<String, Object>>> discharge = CompletableFuture.supplyAsync(() -> {
                await(go);
                return call(HttpMethod.POST, ADMISSIONS + "/" + ca.id() + "/discharge", token,
                        "{'outcome':'HOME','summary':'Ổn'}");
            }, pool);
            go.countDown();

            int recordStatus = recording.get(30, TimeUnit.SECONDS).getStatusCode().value();
            int dischargeStatus = discharge.get(30, TimeUnit.SECONDS).getStatusCode().value();

            assertThat(List.of(recordStatus, dischargeStatus)).as("lần " + i).isIn(List.of(201, 200), List.of(409, 200));
            assertThat(admissionStatus(ca.id())).isEqualTo("DISCHARGED");
            if (recordStatus == 201) {
                recordedFirst++;
                assertThat(footprintOf(ca)).as("ghi trước: đủ ba dấu vết").isEqualTo("1/1/2");
            } else {
                dischargedFirst++;
                assertThat(footprintOf(ca)).as("xuất viện trước: không còn gì").isEqualTo("0/0/0");
            }
        }
        assertThat(recordedFirst + dischargedFirst).isEqualTo(6);
    }

    @Test
    void twoRecordingsAtOnceSerialiseOnTheAdmissionAndBothSucceed() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<Map<String, Object>>> first = CompletableFuture.supplyAsync(() -> {
            await(go);
            return record(token, a.id(), body(true, "5"));
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> second = CompletableFuture.supplyAsync(() -> {
            await(go);
            return record(token, a.id(), body(true, "6"));
        }, pool);
        go.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(second.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("2/2/4");
    }

    @Test
    void aWeightWaitsForThePetRowLockAndThenAllThreeWritesCommitTogether() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // như đánh dấu mất / sửa hồ sơ thú: khóa dòng thú FOR NO KEY UPDATE, xung đột với FOR SHARE của recordWeight
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", Long.class, a.pet());
            locked.countDown();
            await(release);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> record(token, a.id(), body(true, "5")), pool);
        assertBlocked(request);
        // đang chờ: chưa có gì commit (mục theo dõi nằm trong cùng transaction với dòng cân)
        assertThat(footprintOf(a)).isEqualTo("0/0/0");
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(request.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(footprintOf(a)).isEqualTo("1/1/2");
    }

    // ---------------------------------------------------------------- helpers

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
