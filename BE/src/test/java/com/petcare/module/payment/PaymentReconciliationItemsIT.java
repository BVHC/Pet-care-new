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
class PaymentReconciliationItemsIT extends InventoryItSupport {
    private static final LocalDate DATE = LocalDate.of(2044, 11, 1);
    private static final Instant RESOLVED_AT = Instant.parse("2044-11-02T00:00:00Z");

    @AfterEach
    void cleanItems() {
        jdbc.update("""
                DELETE FROM reconciliation_items WHERE reconciliation_id IN
                    (SELECT id FROM payment_reconciliations WHERE recon_date BETWEEN ? AND ?)
                """, DATE, DATE.plusDays(1));
        jdbc.update("DELETE FROM payment_reconciliations WHERE recon_date BETWEEN ? AND ?", DATE, DATE.plusDays(1));
    }

    @Test
    void listsOnlyItemsForThatRunWithPaginationAndResolvedFilter() {
        long managerId = staff("SUPER_MANAGER", null);
        String manager = token(managerId);
        long run = run(DATE);
        long outside = run(DATE.plusDays(1));
        long unresolved = item(run, "ST14-OPEN", null, null);
        long resolved = item(run, "ST14-RESOLVED", managerId, Timestamp.from(RESOLVED_AT));
        item(outside, "ST14-OUTSIDE", null, null);

        Map<String, Object> page = read(run, "?size=1&page=1", manager);
        assertThat(page).containsEntry("totalElements", 2).containsEntry("page", 1).containsEntry("totalPages", 2);
        assertThat(number(onlyItem(page).get("itemId"))).isEqualTo(resolved);

        Map<String, Object> openPage = read(run, "?resolved=false", manager);
        assertThat(openPage).containsEntry("totalElements", 1).containsEntry("size", 20);
        Map<String, Object> open = onlyItem(openPage);
        assertThat(number(open.get("itemId"))).isEqualTo(unresolved);
        assertThat(open).containsEntry("kind", "MISSING_LOCAL").containsEntry("gatewayRef", "ST14-OPEN")
                .containsEntry("gatewayAmount", 100).containsEntry("resolved", false)
                .containsEntry("resolvedAt", null).containsEntry("resolvedNote", null);

        Map<String, Object> closed = onlyItem(read(run, "?resolved=true", manager));
        assertThat(number(closed.get("itemId"))).isEqualTo(resolved);
        assertThat(closed).containsEntry("resolved", true).containsEntry("resolvedNote", "Đã đối chiếu")
                .containsEntry("resolvedAt", RESOLVED_AT.toString());
    }

    @Test
    void validatesInputChecksParentAndEnforcesRole() {
        long run = run(DATE);
        String manager = token(staff("SUPER_MANAGER", null));
        assertThat((List<?>) read(run, "", manager).get("content")).isEmpty();
        assertError(call(HttpMethod.GET, path(run) + "?resolved=unknown", manager, null),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(call(HttpMethod.GET, path(run) + "?size=0", manager, null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.GET, path(Long.MAX_VALUE), manager, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        String branchManager = token(staff("BRANCH_MANAGER", newBranch()));
        assertError(call(HttpMethod.GET, path(run), branchManager, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, path(run), null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    private Map<String, Object> read(long run, String query, String token) {
        return data(call(HttpMethod.GET, path(run) + query, token, null), HttpStatus.OK);
    }

    private static String path(long run) {
        return "/api/payment-reconciliations/" + run + "/items";
    }

    private static Map<String, Object> onlyItem(Map<String, Object> page) {
        List<?> content = (List<?>) page.get("content");
        assertThat(content).hasSize(1);
        return map(content.getFirst());
    }

    private long run(LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO payment_reconciliations (recon_date, status)
                VALUES (?, 'MISMATCHED') RETURNING id
                """, Long.class, date);
    }

    private long item(long run, String ref, Long actor, Timestamp resolvedAt) {
        return jdbc.queryForObject("""
                INSERT INTO reconciliation_items (reconciliation_id, kind, gateway_ref, gateway_amount,
                                                  resolved_by, resolved_note, resolved_at)
                VALUES (?, 'MISSING_LOCAL', ?, 100, ?, ?, ?) RETURNING id
                """, Long.class, run, ref, actor, actor == null ? null : "Đã đối chiếu", resolvedAt);
    }
}
