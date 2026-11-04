package com.petcare.module.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.dto.CustomerSearchFilter;

/**
 * Tra cứu khách theo tên dùng index trigram không dấu (T17-D05, docs/adr/0104): kế hoạch của <b>chính câu SQL</b> mà
 * {@link CustomerSearchRepository} gửi đi, trên 20 000 khách và 20 000 thú trong transaction được hoàn tác (khuôn
 * {@code NoShowIT}). Đổi câu SQL mà quên khớp {@code f_unaccent(...)} của V33 thì đỏ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerSearchIndexIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate named;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String plan(CustomerSearchFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "EXPLAIN SELECT c.id " + CustomerSearchRepository.from(filter, params);
        List<Map<String, Object>> rows = named.queryForList(sql, params);
        return String.join("\n", rows.stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).toList());
    }

    private void inRolledBackTransaction(Runnable work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    SELECT 'Khách ' || g || ' Nguyễn Văn', '09' || lpad(g::text, 8, '0'), 'COUNTER'
                    FROM generate_series(1, 20000) g""");
            jdbc.update("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    VALUES ('Đặng Zxqvw Hưng', '0999999999', 'COUNTER')""");
            jdbc.update("""
                    INSERT INTO pets (customer_id, name, species, sex)
                    SELECT c.id, 'Bé ' || c.id || ' Mướp', 'CAT', 'MALE' FROM customers c
                    WHERE c.full_name LIKE 'Khách %' LIMIT 20000""");
            jdbc.update("""
                    INSERT INTO pets (customer_id, name, species, sex)
                    SELECT id, 'Mèo Qwxyz', 'CAT', 'MALE' FROM customers WHERE phone = '0999999999'""");
            jdbc.execute("ANALYZE customers");
            jdbc.execute("ANALYZE pets");
            try {
                work.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void searchByNameUsesTheUnaccentedTrigramIndex() {
        inRolledBackTransaction(() -> {
            String plan = plan(new CustomerSearchFilter(null, "dang zxqvw", null, null));

            assertThat(plan).contains("ix_customers_full_name_unaccent_trgm");
            assertThat(plan).doesNotContain("Seq Scan on customers");
        });
    }

    @Test
    void searchByPetNameUsesTheUnaccentedTrigramIndex() {
        inRolledBackTransaction(() -> {
            String plan = plan(new CustomerSearchFilter(null, null, null, "meo qwxyz"));

            assertThat(plan).contains("ix_pets_name_unaccent_trgm");
        });
    }
}
