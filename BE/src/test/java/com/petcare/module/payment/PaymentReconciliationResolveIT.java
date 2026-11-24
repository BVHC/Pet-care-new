package com.petcare.module.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.InventoryItSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jobs.payment-reconciliation.cron=-")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentReconciliationResolveIT extends InventoryItSupport {
    private static final LocalDate DATE = LocalDate.of(2044, 12, 1);

    @AfterEach
    void cleanItems() {
        jdbc.update("""
                DELETE FROM reconciliation_items WHERE reconciliation_id IN
                    (SELECT id FROM payment_reconciliations WHERE recon_date = ?)
                """, DATE);
        jdbc.update("DELETE FROM payment_reconciliations WHERE recon_date = ?", DATE);
    }

    @Test
    void storesTheActorNoteAndTimestampAndRejectsASecondResolution() {
        long item = unresolvedItem();
        long managerId = staff("SUPER_MANAGER", null);
        String manager = token(managerId);
        String other = token(staff("SUPER_MANAGER", null));
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM payment_reconciliations WHERE recon_date = ?", DATE);

        Map<String, Object> response = data(resolve(item, manager, "  Đã kiểm tra  "), HttpStatus.OK);
        assertThat(number(response.get("itemId"))).isEqualTo(item);
        assertThat(response).containsEntry("resolved", true).containsEntry("resolvedNote", "Đã kiểm tra")
                .containsEntry("kind", "MISSING_LOCAL").containsEntry("gatewayRef", "ST14-RESOLVE");
        Map<String, Object> row = row(item);
        assertThat(row).containsEntry("resolved_by", managerId).containsEntry("resolved_note", "Đã kiểm tra");
        assertThat(((Timestamp) row.get("resolved_at")).toInstant())
                .isEqualTo(Instant.parse((String) response.get("resolvedAt")));

        assertError(resolve(item, other, "Ghi đè"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(row(item)).isEqualTo(row);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_reconciliations WHERE recon_date = ?", DATE)).isEqualTo(run);
    }

    @Test
    void rejectsInvalidBodyMissingItemAndUnauthorizedUsers() {
        long item = unresolvedItem();
        Map<String, Object> before = row(item);
        String manager = token(staff("SUPER_MANAGER", null));
        assertError(resolve(item, manager, "   "), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(resolve(item, manager, "x".repeat(301)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, path(item), manager, Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(resolve(Long.MAX_VALUE, manager, "Đã kiểm tra"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        String receptionist = token(staff("RECEPTIONIST", newBranch()));
        assertError(resolve(item, receptionist, "Đã kiểm tra"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(resolve(item, null, "Đã kiểm tra"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(row(item)).isEqualTo(before);
    }

    @Test
    void competingManagersLeaveExactlyOneNote() throws Exception {
        long item = unresolvedItem();
        long firstId = staff("SUPER_MANAGER", null);
        long secondId = staff("SUPER_MANAGER", null);
        String firstToken = token(firstId);
        String secondToken = token(secondId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> first = workers.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return resolve(item, firstToken, "Ghi chú thứ nhất");
            });
            Future<ResponseEntity<Map<String, Object>>> second = workers.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return resolve(item, secondToken, "Ghi chú thứ hai");
            });
            start.countDown();

            var firstResponse = first.get(15, TimeUnit.SECONDS);
            var secondResponse = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(firstResponse.getStatusCode(), secondResponse.getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            boolean firstSucceeded = firstResponse.getStatusCode() == HttpStatus.OK;
            assertThat(row(item)).containsEntry("resolved_by", firstSucceeded ? firstId : secondId)
                    .containsEntry("resolved_note", firstSucceeded ? "Ghi chú thứ nhất" : "Ghi chú thứ hai");
        } finally {
            workers.shutdownNow();
        }
    }

    private ResponseEntity<Map<String, Object>> resolve(long id, String token, String note) {
        return call(HttpMethod.POST, path(id), token, Map.of("note", note));
    }

    private static String path(long id) {
        return "/api/payment-reconciliation-items/" + id + "/resolve";
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM reconciliation_items WHERE id = ?", id);
    }

    private long unresolvedItem() {
        return jdbc.queryForObject("""
                WITH run AS (
                    INSERT INTO payment_reconciliations (recon_date, status, txn_count, mismatch_count)
                    VALUES (?, 'MISMATCHED', 1, 1)
                    RETURNING id
                )
                INSERT INTO reconciliation_items (reconciliation_id, kind, gateway_ref, gateway_amount)
                SELECT id, 'MISSING_LOCAL', 'ST14-RESOLVE', 100 FROM run
                RETURNING id
                """, Long.class, DATE);
    }
}
