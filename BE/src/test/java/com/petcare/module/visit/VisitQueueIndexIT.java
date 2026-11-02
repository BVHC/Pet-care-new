package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * Index của hàng đợi trên dữ liệu gần thật (docs/adr/0060 mục 9; khuôn {@code NoShowIT.overdueQueriesUseTheirIndexesOn
 * RealisticData}): 20 000 lượt ở 20 chi nhánh, 95 % đã kết thúc, chèn trong transaction rollback + {@code ANALYZE}.
 * {@code EXPLAIN} câu tương đương câu Hibernate sinh cho hàng đợi mặc định (#2), hàng đợi của tôi (#3), tra Order theo
 * lượt và lọc theo ngày check-in. Lọc theo ngày dùng {@code ix_visits_branch_checked_in_at} (V30, docs/adr/0095, trả
 * D041), cả khi kế hoạch là generic plan (pgjdbc chuyển sang sau 5 lần thực thi).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitQueueIndexIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void queueQueriesUseTheirIndexesOnRealisticData() {
        Map<String, String> plans = new TransactionTemplate(transactionManager).execute(s -> {
            s.setRollbackOnly();
            long branch = seed();
            long vet = jdbc.queryForObject("SELECT min(id) FROM accounts WHERE email LIKE 'vq-explain-%'", Long.class);
            String someVisits = jdbc.queryForObject("""
                    SELECT string_agg(id::text, ',') FROM (SELECT id FROM visits WHERE code LIKE 'VQX-%' LIMIT 100) x""",
                    String.class);
            Timestamp dayStart = Timestamp.from(LocalDate.of(2025, 3, 1).atStartOfDay(VN).toInstant());
            Timestamp dayEnd = Timestamp.from(LocalDate.of(2025, 3, 2).atStartOfDay(VN).toInstant());

            Map<String, String> result = new HashMap<>();
            result.put("queue", explain("""
                    SELECT * FROM visits WHERE branch_id = ? AND status IN (?, ?)""", branch, "WAITING",
                    "IN_PROGRESS"));
            result.put("myQueue", explain("""
                    SELECT * FROM visits WHERE assignee_id = ? AND status IN (?, ?)""", vet, "WAITING",
                    "IN_PROGRESS"));
            result.put("orders", explain("SELECT visit_id, id FROM orders WHERE visit_id IN (" + someVisits + ")"));
            result.put("byDate", explain("""
                    SELECT * FROM visits WHERE branch_id = ? AND checked_in_at >= ? AND checked_in_at < ?""", branch,
                    dayStart, dayEnd));
            result.put("byDateAndStatus", explain("""
                    SELECT * FROM visits WHERE branch_id = ? AND status = ? AND checked_in_at >= ?
                    AND checked_in_at < ?""", branch, "COMPLETED", dayStart, dayEnd));
            return result;
        });

        assertThat(plans.get("queue")).doesNotContain("Seq Scan on visits").contains("ix_visits_queue");
        assertThat(plans.get("myQueue")).doesNotContain("Seq Scan on visits").contains("ix_visits_assignee_status");
        assertThat(plans.get("orders")).doesNotContain("Seq Scan on orders").contains("uq_orders_visit_id");
        // D041 (V30): branch_id và checked_in_at cùng nằm trong điều kiện của index, không còn Filter theo ngày.
        assertThat(plans.get("byDate")).as(plans.get("byDate")).contains("ix_visits_branch_checked_in_at")
                .doesNotContain("Seq Scan on visits").doesNotContain("Filter: ((checked_in_at")
                .doesNotContain("Filter: (checked_in_at");
        assertThat(plans.get("byDateAndStatus")).as(plans.get("byDateAndStatus"))
                .contains("ix_visits_branch_checked_in_at").doesNotContain("Seq Scan on visits");
    }

    /**
     * Hibernate gửi tham số bind; sau 5 lần thực thi pgjdbc có thể chuyển sang generic plan. Ép generic plan bằng
     * {@code plan_cache_mode} và kiểm câu theo ngày (có và không có {@code status}) vẫn đi index mới.
     */
    @Test
    void byDateUsesTheIndexWithAGenericPlan() {
        Map<String, String> plans = new TransactionTemplate(transactionManager).execute(s -> {
            s.setRollbackOnly();
            long branch = seed();
            jdbc.execute("SET LOCAL plan_cache_mode = force_generic_plan");
            jdbc.execute("PREPARE visits_by_date (bigint, timestamptz, timestamptz) AS SELECT * FROM visits"
                    + " WHERE branch_id = $1 AND checked_in_at >= $2 AND checked_in_at < $3");
            jdbc.execute("PREPARE visits_by_date_status (bigint, varchar, timestamptz, timestamptz) AS"
                    + " SELECT * FROM visits WHERE branch_id = $1 AND status = $2 AND checked_in_at >= $3"
                    + " AND checked_in_at < $4");
            String from = "TIMESTAMPTZ '2025-03-01 00:00+07'";
            String to = "TIMESTAMPTZ '2025-03-02 00:00+07'";

            Map<String, String> result = new HashMap<>();
            result.put("byDate", explain("EXECUTE visits_by_date(" + branch + ", " + from + ", " + to + ")"));
            result.put("byDateAndStatus", explain("EXECUTE visits_by_date_status(" + branch + ", 'COMPLETED', "
                    + from + ", " + to + ")"));
            return result;
        });

        assertThat(plans.get("byDate")).as(plans.get("byDate")).contains("ix_visits_branch_checked_in_at")
                .doesNotContain("Seq Scan on visits");
        assertThat(plans.get("byDateAndStatus")).as(plans.get("byDateAndStatus"))
                .contains("ix_visits_branch_checked_in_at").doesNotContain("Seq Scan on visits");
    }

    /** Chèn 20 chi nhánh, 200 VET, 2000 khách / thú, 20 000 lượt + Order, {@code ANALYZE}; trả id chi nhánh đầu tiên. */
    private long seed() {
        jdbc.update("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status, activated_at)
                SELECT 'VQ-EXPLAIN ' || g, 'Địa chỉ', '0280000000', 10.76, 106.66, 'ACTIVE', now()
                FROM generate_series(1, 20) g""");
        jdbc.update("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                SELECT 'vq-explain-' || g || '@example.com', '0901234567', 'x', 'VET', 'ACTIVE'
                FROM generate_series(1, 200) g""");
        jdbc.update("""
                INSERT INTO customers (full_name, phone, created_channel)
                SELECT 'VQ-EXPLAIN ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 2000) g""");
        jdbc.update("""
                INSERT INTO pets (customer_id, name, species, sex)
                SELECT id, 'VQ-EXPLAIN', 'DOG', 'MALE' FROM customers WHERE full_name LIKE 'VQ-EXPLAIN %'""");
        jdbc.update("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at)
                SELECT 'VQX-' || g, b.id, p.customer_id, p.id, 'MEDICAL',
                       CASE WHEN g % 20 = 0 THEN 'WAITING' WHEN g % 20 = 1 THEN 'IN_PROGRESS'
                            WHEN g % 2 = 0 THEN 'COMPLETED' ELSE 'CANCELLED' END,
                       'WALK_IN', TIMESTAMPTZ '2025-01-01 08:00+07' + (g / 50) * INTERVAL '1 day',
                       a.id, a.id, TIMESTAMPTZ '2025-01-01 08:00+07' + (g / 50) * INTERVAL '1 day'
                FROM generate_series(0, 19999) g
                JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n FROM branches
                      WHERE name LIKE 'VQ-EXPLAIN %') b ON b.n = g % 20
                JOIN (SELECT id, customer_id, row_number() OVER (ORDER BY id) - 1 AS n FROM pets
                      WHERE name = 'VQ-EXPLAIN') p ON p.n = g % 2000
                JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n FROM accounts
                      WHERE email LIKE 'vq-explain-%') a ON a.n = g % 200""");
        jdbc.update("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                SELECT 'VQO-' || v.id, 'VISIT', v.customer_id, v.branch_id, v.id, 'OPEN', v.checked_in_by
                FROM visits v WHERE v.code LIKE 'VQX-%'""");
        jdbc.execute("ANALYZE visits");
        jdbc.execute("ANALYZE orders");
        return jdbc.queryForObject("SELECT min(id) FROM branches WHERE name LIKE 'VQ-EXPLAIN %'", Long.class);
    }

    private String explain(String sql, Object... args) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
    }
}
