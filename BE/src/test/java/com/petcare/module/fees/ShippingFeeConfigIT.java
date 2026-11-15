package com.petcare.module.fees;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.fees.api.ShippingFeeApi;
import com.petcare.module.fees.api.ShippingFeeQuote;
import com.petcare.module.identity.service.SessionService;

/**
 * Cấu hình phí vận chuyển qua HTTP trên Postgres thật (fees-v1 #1 {@code GET}, #2 {@code PUT /shipping-fee-config};
 * BR-SP-08, 11; docs/adr/0140) và ST09 qua {@link ShippingFeeApi}. Bảng chỉ có một dòng {@code id = 1} (V43) nên mỗi test
 * trả nó về (0, 0) sau khi chạy. Không có job quét theo đồng hồ nên không cần era riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShippingFeeConfigIT {

    private static final String TAG = "ShippingFeeConfigIT";
    private static final String PATH = "/api/shipping-fee-config";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ShippingFeeApi shippingFee;

    private AppointmentItSupport support;
    private TransactionTemplate tx;
    private long managerId;
    private String manager;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        support = new AppointmentItSupport(jdbc);
        resetSeed();
        managerId = support.staff("SUPER_MANAGER", null);
        manager = token(managerId);
    }

    @AfterEach
    void tearDown() {
        resetSeed();
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'shipping_fee_configs' AND actor_account_id = ?",
                managerId);
        support.cleanUp();
    }

    /** Giá trị gieo của V43. */
    private void resetSeed() {
        jdbc.update("UPDATE shipping_fee_configs SET base_fee = 0, free_threshold = 0, updated_by = NULL WHERE id = 1");
    }

    // ================================================================ đọc / ghi

    @Test
    void getReturnsTheSeededConfigToSuperManager() {
        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.GET, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = data(response);
        assertThat(data).containsEntry("baseFee", 0).containsEntry("freeThreshold", 0);
        assertThat(data.get("updatedAt")).isNotNull();
    }

    @Test
    void putPersistsReplacesBothValuesAndAuditsOnce() {
        ResponseEntity<Map<String, Object>> put = call(manager, HttpMethod.PUT, body(30_000, 500_000));

        assertThat(put.getStatusCode().value()).isEqualTo(200);
        assertThat(data(put)).containsEntry("baseFee", 30_000).containsEntry("freeThreshold", 500_000);
        assertThat(data(call(manager, HttpMethod.GET, null))).containsEntry("baseFee", 30_000)
                .containsEntry("freeThreshold", 500_000);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM shipping_fee_configs WHERE id = 1", Long.class))
                .isEqualTo(managerId);

        List<Map<String, Object>> audits = audits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "SHIPPING_FEE_CONFIG_UPDATED")
                .containsEntry("entity_id", 1L);
        assertThat((String) audits.get(0).get("before_data")).contains("\"baseFee\": 0", "\"freeThreshold\": 0");
        assertThat((String) audits.get(0).get("after_data")).contains("\"baseFee\": 30000", "\"freeThreshold\": 500000");
    }

    @Test
    void anIdenticalPutStillAudits() {
        call(manager, HttpMethod.PUT, body(10, 20));
        call(manager, HttpMethod.PUT, body(10, 20));

        assertThat(audits()).hasSize(2);
    }

    @Test
    void shippingFeeApiSeesTheNewValuesAfterPut() {
        assertThat(shippingFee.quote(1_000_000)).isEqualTo(new ShippingFeeQuote(0, 0, 0));

        call(manager, HttpMethod.PUT, body(30_000, 500_000));

        assertThat(shippingFee.quote(499_999)).isEqualTo(new ShippingFeeQuote(30_000, 30_000, 500_000));
        assertThat(shippingFee.quote(500_000)).isEqualTo(new ShippingFeeQuote(0, 30_000, 500_000));
    }

    // ================================================================ phân quyền

    @Test
    void onlySuperManagerMayReadOrWrite() {
        String branchManager = token(support.staff("BRANCH_MANAGER", support.branch("ACTIVE")));
        String customer = token(support.customerAccount());

        for (String bearer : List.of(branchManager, customer)) {
            assertThat(call(bearer, HttpMethod.GET, null).getStatusCode().value()).isEqualTo(403);
            assertThat(call(bearer, HttpMethod.PUT, body(1, 1)).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(call(null, HttpMethod.GET, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(null, HttpMethod.PUT, body(1, 1)).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT base_fee FROM shipping_fee_configs WHERE id = 1", Long.class)).isZero();
        assertThat(audits()).isEmpty();
    }

    // ================================================================ đầu vào sai

    @Test
    void negativeValuesAreBrSp08AndWriteNothing() {
        for (String json : List.of("{\"baseFee\":-1,\"freeThreshold\":0}", "{\"baseFee\":0,\"freeThreshold\":-1}")) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PUT, json);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat((String) response.getBody().get("message")).contains("(BR-SP-08)");
        }
        assertThat(audits()).isEmpty();
    }

    @Test
    void aMissingFieldIsValidationFailed() {
        for (String json : List.of("{\"baseFee\":1}", "{\"freeThreshold\":1}", "{}")) {
            ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PUT, json);

            assertThat(response.getStatusCode().value()).as(json).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        }
        assertThat(audits()).isEmpty();
    }

    @Test
    void aFloatIsMalformedRequest() {
        ResponseEntity<Map<String, Object>> response = call(manager, HttpMethod.PUT,
                "{\"baseFee\":1.5,\"freeThreshold\":0}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("errorCode", "MALFORMED_REQUEST");
        assertThat(audits()).isEmpty();
    }

    // ================================================================ khóa

    /** Hai người sửa cùng lúc: PUT xếp hàng sau khóa dòng 1 và audit {@code before} là giá trị vừa commit, không phải giá trị cũ. */
    @Test
    void putWaitsForTheRowLockAndAuditsTheValuesCommittedMeanwhile() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM shipping_fee_configs WHERE id = 1 FOR NO KEY UPDATE");
                held.countDown();
                await(release);
                jdbc.update("UPDATE shipping_fee_configs SET base_fee = 7, free_threshold = 70 WHERE id = 1");
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> put = pool
                    .submit(() -> call(manager, HttpMethod.PUT, body(30_000, 500_000)));
            awaitWaiting("%shipping_fee_configs%");
            assertThat(put.isDone()).as("PUT phải chờ khóa").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(put.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        List<Map<String, Object>> audits = audits();
        assertThat(audits).hasSize(1);
        assertThat((String) audits.get(0).get("before_data")).contains("\"baseFee\": 7", "\"freeThreshold\": 70");
        assertThat((String) audits.get(0).get("after_data")).contains("\"baseFee\": 30000");
    }

    // ================================================================ helpers

    private List<Map<String, Object>> audits() {
        return new ArrayList<>(jdbc.queryForList("""
                SELECT action, entity_id, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE entity_type = 'shipping_fee_configs' AND actor_account_id = ? ORDER BY id""",
                managerId));
    }

    private void awaitWaiting(String like) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE ?""", Long.class, like);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy PUT chờ khóa");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String body(long baseFee, long freeThreshold) {
        return "{\"baseFee\":" + baseFee + ",\"freeThreshold\":" + freeThreshold + "}";
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", TAG)).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, HttpMethod method, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange(URI.create(http.getRootUri() + PATH), method, new HttpEntity<>(json, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as("%s", response.getBody()).isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }
}
