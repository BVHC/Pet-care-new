package com.petcare.module.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inventory.InventoryItSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jobs.payment-reconciliation.cron=-")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentReconciliationListIT extends InventoryItSupport {
    private static final String PATH = "/api/payment-reconciliations";
    private static final LocalDate DATE = LocalDate.of(2044, 10, 1);

    @AfterEach
    void cleanRuns() {
        jdbc.update("DELETE FROM payment_reconciliations WHERE recon_date BETWEEN ? AND ?", DATE, DATE.plusDays(2));
    }

    @Test
    void managerListsNewestFirstWithStatusFilterAndPagination() {
        long older = run(DATE, "MATCHED");
        long newer = run(DATE.plusDays(1), "MATCHED");
        long mismatch = run(DATE.plusDays(2), "MISMATCHED");
        String manager = token(staff("SUPER_MANAGER", null));

        Map<String, Object> newestPage = read("?page=0&size=1", manager);
        assertThat(newestPage).containsEntry("totalElements", 3).containsEntry("totalPages", 3)
                .containsEntry("page", 0).containsEntry("size", 1);
        Map<String, Object> summary = onlySummary(newestPage);
        assertThat(number(summary.get("reconciliationId"))).isEqualTo(mismatch);
        assertThat(summary).containsEntry("reconDate", DATE.plusDays(2).toString())
                .containsEntry("status", "MISMATCHED").containsEntry("txnCount", 4).containsEntry("mismatchCount", 1)
                .containsEntry("finishedAt", "2044-10-04T00:00:00Z");

        Map<String, Object> match = read("?status=MATCHED&page=0&size=1", manager);
        assertThat(match).containsEntry("totalElements", 2);
        assertThat(number(onlySummary(match).get("reconciliationId"))).isEqualTo(newer);
        assertThat(number(onlySummary(read("?status=MATCHED&page=1&size=1", manager))
                .get("reconciliationId"))).isEqualTo(older);
        assertThat((List<?>) read("?status=FAILED", manager).get("content")).isEmpty();
    }

    @Test
    void rejectsInvalidFiltersAndOnlyAllowsSuperManager() {
        String manager = token(staff("SUPER_MANAGER", null));
        assertError(call(HttpMethod.GET, PATH + "?status=BOGUS", manager, null),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(call(HttpMethod.GET, PATH + "?page=-1", manager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, PATH + "?size=101", manager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, PATH, token(staff("ADMIN", null)), null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    private Map<String, Object> read(String query, String token) {
        return data(call(HttpMethod.GET, PATH + query, token, null), HttpStatus.OK);
    }

    private static Map<String, Object> onlySummary(Map<String, Object> page) {
        List<?> content = (List<?>) page.get("content");
        assertThat(content).hasSize(1);
        return map(content.getFirst());
    }

    private long run(LocalDate date, String status) {
        return jdbc.queryForObject("""
                INSERT INTO payment_reconciliations (recon_date, status, txn_count, mismatch_count, finished_at)
                VALUES (?, ?, 4, ?, ?) RETURNING id
                """, Long.class, date, status, "MATCHED".equals(status) ? 0 : 1,
                Timestamp.from(Instant.parse("2044-10-04T00:00:00Z")));
    }
}
