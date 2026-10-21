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
 * {@code EXPLAIN} câu tương đương câu Hibernate sinh cho hàng đợi mặc định (#2), hàng đợi của tôi (#3) và tra Order theo
 * lượt. Lọc theo ngày check-in không có index riêng: chỉ ghi lại plan (nợ D041), không assert index.
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

            long branch = jdbc.queryForObject("SELECT min(id) FROM branches WHERE name LIKE 'VQ-EXPLAIN %'",
                    Long.class);
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
            return result;
        });

        assertThat(plans.get("queue")).doesNotContain("Seq Scan on visits").contains("ix_visits_queue");
        assertThat(plans.get("myQueue")).doesNotContain("Seq Scan on visits").contains("ix_visits_assignee_status");
        assertThat(plans.get("orders")).doesNotContain("Seq Scan on orders").contains("uq_orders_visit_id");
        // Nợ D041: không có index (branch_id, checked_in_at) — chỉ ghi lại plan để so khi trả nợ.
        System.out.println("VisitQueueIndexIT byDate plan (D041):\n" + plans.get("byDate"));
        assertThat(plans.get("byDate")).isNotBlank();
    }

    private String explain(String sql, Object... args) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
    }
}
