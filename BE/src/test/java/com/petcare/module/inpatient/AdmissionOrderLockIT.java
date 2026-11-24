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
 * Tranh chấp của y lệnh nội trú (docs/adr/0149 mục 28) trên Postgres thật. Bên giữ khóa là một transaction JDBC ở luồng
 * riêng; request phải <b>chưa xong</b> sau 1,5 s (đang chờ khóa), rồi sau khi bên giữ commit thì <b>đọc lại</b> trạng thái
 * mới. Các test chứng minh:
 * <ul>
 * <li>ra y lệnh khóa dòng ca <b>trước</b> khi ghi (ràng buộc của ADR-0149 mục 23): chờ xuất viện đang giữ ca, rồi thấy ca
 * {@code DISCHARGED} → 409; hai thao tác xuất viện / ra y lệnh chạy đồng thời không bao giờ để lại y lệnh {@code ORDERED}
 * trên ca đã xuất viện;</li>
 * <li>thực hiện / hủy khóa dòng y lệnh rồi mới đọc, ở READ COMMITTED: bên thua nhận 409 {@code INVALID_STATE_TRANSITION},
 * không phải {@code CONCURRENCY_CONFLICT};</li>
 * <li>ra y lệnh thuốc giữ {@code FOR KEY SHARE} dòng đơn: chờ xóa dòng đơn đang giữ khóa rồi thấy "không có" → 400
 * {@code BR-LT-15}, không 409 chung chung.</li>
 * </ul>
 * Era <b>năm 2104</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionOrderLockIT.TestBeans.class})
class AdmissionOrderLockIT extends AdmissionOrderItBase {

    private static final Instant NOW = Instant.parse("2104-03-14T02:00:00Z");
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
        customer = data.customer();
        service = enabledInpatientService(branch, 200_000L);
        token = token(vet);
        a = admitFromVisit(branch, customer, service, vet, token);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        finish();
    }

    // ---------------------------------------------------------------- #5 khóa dòng ca trước khi ghi

    @Test
    void creatingAnOrderWaitsForTheAdmissionLockAndThenSeesTheDischargedAdmission() throws Exception {
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
                CompletableFuture.supplyAsync(() -> createOrder(token, a.id(), feeding("Thêm")), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertStatus(request.get(30, TimeUnit.SECONDS), 409, "INVALID_STATE_TRANSITION");
        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void creatingAnOrderProceedsOnceTheLockHolderCommitsWithoutChange() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admissions WHERE id = ? FOR NO KEY UPDATE", Long.class, a.id());
            locked.countDown();
            await(release);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> createOrder(token, a.id(), feeding("Thêm")), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(request.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(orderRows(a.id())).isEqualTo(1);
    }

    /**
     * Xuất viện (đếm {@code ORDERED} dưới khóa ca) chạy đồng thời với ra y lệnh: dù ai tới trước, ca đã xuất viện không
     * bao giờ còn y lệnh {@code ORDERED}. Lặp vài ca để cả hai thứ tự đều có cơ hội xảy ra.
     */
    @Test
    void dischargeAndCreateRunningTogetherNeverLeaveAnOrderedOrderOnADischargedAdmission() throws Exception {
        int created = 0;
        int discharged = 0;
        for (int i = 0; i < 6; i++) {
            Admitted ca = admitFromVisit(branch, customer, service, vet, token);
            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<ResponseEntity<Map<String, Object>>> create = CompletableFuture.supplyAsync(() -> {
                await(go);
                return createOrder(token, ca.id(), feeding("Thêm"));
            }, pool);
            CompletableFuture<ResponseEntity<Map<String, Object>>> discharge = CompletableFuture.supplyAsync(() -> {
                await(go);
                return call(HttpMethod.POST, ADMISSIONS + "/" + ca.id() + "/discharge", token,
                        "{'outcome':'HOME','summary':'Ổn'}");
            }, pool);
            go.countDown();

            int createStatus = create.get(30, TimeUnit.SECONDS).getStatusCode().value();
            int dischargeStatus = discharge.get(30, TimeUnit.SECONDS).getStatusCode().value();

            // Chỉ hai kết quả hợp lệ: (201, 400 còn y lệnh) hoặc (409 ca đã xuất viện, 200).
            assertThat(List.of(createStatus, dischargeStatus)).as("lần " + i).isIn(List.of(201, 400), List.of(409, 200));
            if (createStatus == 201) {
                created++;
                assertThat(admissionStatus(ca.id())).isEqualTo("ADMITTED");
            } else {
                discharged++;
                assertThat(admissionStatus(ca.id())).isEqualTo("DISCHARGED");
                assertThat(orderRows(ca.id())).isZero();
            }
        }
        assertThat(created + discharged).isEqualTo(6);
    }

    @Test
    void twoCreatesAtOnceSerialiseOnTheAdmissionAndBothSucceed() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<Map<String, Object>>> first = CompletableFuture.supplyAsync(() -> {
            await(go);
            return createOrder(token, a.id(), feeding("Một"));
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> second = CompletableFuture.supplyAsync(() -> {
            await(go);
            return createOrder(token, a.id(), feeding("Hai"));
        }, pool);
        go.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(second.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        assertThat(orderRows(a.id())).isEqualTo(2);
    }

    // ---------------------------------------------------------------- #7, #8 khóa dòng y lệnh

    @Test
    void completeWaitsForTheOrderLockThenSees409NotAConcurrencyConflict() throws Exception {
        long order = newFeeding(token, a.id());

        CompletableFuture<ResponseEntity<Map<String, Object>>> request = whileAnotherTransactionFinishesTheOrder(order,
                () -> complete(token, order, "Muộn"));

        assertStatus(request.get(30, TimeUnit.SECONDS), 409, "INVALID_STATE_TRANSITION");
        assertThat(orderStatus(order)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT done_note FROM admission_orders WHERE id = ?", String.class, order))
                .isEqualTo("Bên giữ khóa");
    }

    @Test
    void cancelWaitsForTheOrderLockThenSees409NotAConcurrencyConflict() throws Exception {
        long order = newFeeding(token, a.id());

        CompletableFuture<ResponseEntity<Map<String, Object>>> request = whileAnotherTransactionFinishesTheOrder(order,
                () -> cancel(token, order, "Muộn"));

        assertStatus(request.get(30, TimeUnit.SECONDS), 409, "INVALID_STATE_TRANSITION");
        assertThat(orderStatus(order)).isEqualTo("DONE");
    }

    @Test
    void twoCompletionsOfTheSameOrderAtOnceGiveExactlyOneSuccess() throws Exception {
        long order = newFeeding(token, a.id());
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<Map<String, Object>>> first = CompletableFuture.supplyAsync(() -> {
            await(go);
            return complete(token, order, "Một");
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> second = CompletableFuture.supplyAsync(() -> {
            await(go);
            return complete(token, order, "Hai");
        }, pool);
        go.countDown();

        List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                second.get(30, TimeUnit.SECONDS).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ResponseEntity<Map<String, Object>> loser = statuses.get(0) == 409 ? first.get() : second.get();
        assertThat(errorCode(loser)).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(orderStatus(order)).isEqualTo("DONE");
    }

    @Test
    void aCompletionAndACancellationAtOnceGiveExactlyOneWinnerAndTheLoserSees409() throws Exception {
        long order = newFeeding(token, a.id());
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<Map<String, Object>>> done = CompletableFuture.supplyAsync(() -> {
            await(go);
            return complete(token, order, "Xong");
        }, pool);
        CompletableFuture<ResponseEntity<Map<String, Object>>> cancelled = CompletableFuture.supplyAsync(() -> {
            await(go);
            return cancel(token, order, "Hủy");
        }, pool);
        go.countDown();

        List<Integer> statuses = List.of(done.get(30, TimeUnit.SECONDS).getStatusCode().value(),
                cancelled.get(30, TimeUnit.SECONDS).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String status = orderStatus(order);
        assertThat(status).isIn("DONE", "CANCELLED");
        // cột của bên thua không được lẫn vào: DONE ⇒ không có lý do hủy, CANCELLED ⇒ không có ghi chú thực hiện
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT done_by, done_note, cancel_reason FROM admission_orders WHERE id = ?", order);
        if (status.equals("DONE")) {
            assertThat(row.get("cancel_reason")).isNull();
        } else {
            assertThat(row.get("done_by")).isNull();
            assertThat(row.get("done_note")).isNull();
        }
    }

    // ---------------------------------------------------------------- #5 khóa dòng đơn thuốc FOR KEY SHARE

    @Test
    void aMedicationOrderWaitsForALineThatIsBeingDeletedAndThenGets400NotAConflict() throws Exception {
        long item = data.clinicPrescriptionItem(a.visit(), vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            // khóa của visit-v1 #17: FOR UPDATE rồi DELETE
            jdbc.queryForObject("SELECT id FROM prescription_items WHERE id = ? FOR UPDATE", Long.class, item);
            locked.countDown();
            await(release);
            jdbc.update("DELETE FROM prescription_items WHERE id = ?", item);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> createOrder(token, a.id(), medication(item)), pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertRule(request.get(30, TimeUnit.SECONDS), "BR-LT-15");
        assertThat(orderRows(a.id())).isZero();
    }

    @Test
    void aKeyShareHolderDoesNotBlockAnotherMedicationOrderOnTheSameLine() throws Exception {
        long item = data.clinicPrescriptionItem(a.visit(), vet);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // bên giữ KEY SHARE dòng đơn như request ra y lệnh đang chạy giữa transaction
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM prescription_items WHERE id = ? FOR KEY SHARE", Long.class, item);
            locked.countDown();
            await(release);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // một request ra y lệnh thuốc khác không bị chặn bởi KEY SHARE (cùng sức khóa nên không xung đột)
        ResponseEntity<Map<String, Object>> parallel = createOrder(token, a.id(), medication(item));
        assertThat(parallel.getStatusCode().value()).as(String.valueOf(parallel.getBody())).isEqualTo(201);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Một transaction khác giữ khóa dòng y lệnh, đã chuyển nó sang {@code DONE} (ghi chú "Bên giữ khóa") và chỉ commit sau
     * khi request đã chờ; trả về request đang chờ (chưa xong).
     */
    private CompletableFuture<ResponseEntity<Map<String, Object>>> whileAnotherTransactionFinishesTheOrder(long order,
            java.util.function.Supplier<ResponseEntity<Map<String, Object>>> call) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM admission_orders WHERE id = ? FOR NO KEY UPDATE", Long.class, order);
            locked.countDown();
            await(release);
            jdbc.update("""
                    UPDATE admission_orders SET status = 'DONE', done_by = ?, done_at = now(), done_note = 'Bên giữ khóa'
                    WHERE id = ?""", vet, order);
        }), pool);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<ResponseEntity<Map<String, Object>>> request = CompletableFuture.supplyAsync(call, pool);
        assertBlocked(request);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return request;
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
