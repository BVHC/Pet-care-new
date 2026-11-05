package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * V28 (docs/adr/0093, trả D043 nhóm 1): bốn câu đọc / kiểm FK theo bệnh án, lượt và loại vaccine phải đi index mới trên
 * dữ liệu gần thật. 20 000 dòng chèn trong transaction rollback + {@code ANALYZE}, khuôn
 * {@code NoShowIT.overdueQueriesUseTheirIndexesOnRealisticData}. Khóa ngoại tắt bằng {@code session_replication_role =
 * replica} (chỉ trong transaction này) để không dựng cả chuỗi bệnh án / lượt / mũi tiêm: bài kiểm chỉ về kế hoạch truy
 * vấn. Id giả bắt đầu từ {@link #BASE} nên không đụng dữ liệu thật của IT khác; mọi thứ rollback nên không cần era riêng.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FkIndexScanIT {

    /** Id giả của bệnh án / lượt / loại vaccine, xa mọi id IDENTITY thật. */
    private static final long BASE = 900_000_000L;
    private static final int ROWS = 20_000;
    private static final int PARENTS = 2_000;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 20 000 dòng chèn rồi rollback để lại dòng chết và thống kê lệch trên ba bảng; IT chọn kế hoạch theo thống kê của
     * {@code vaccinations} chạy sau trong cùng JVM (vd. {@code ReminderScanIndexIT}) có thể đổi index. VACUUM thường
     * không thu nhỏ heap và index đã phình sau rollback nên {@code relpages} vẫn lệch; VACUUM FULL ngoài transaction viết
     * lại bảng và index, trả về kích thước trước IT này.
     */
    @AfterEach
    void vacuumWhatTheRolledBackRowsLeftBehind() {
        jdbc.execute("VACUUM (FULL, ANALYZE) medical_record_addenda");
        jdbc.execute("VACUUM (FULL, ANALYZE) prescription_items");
        jdbc.execute("VACUUM (FULL, ANALYZE) vaccinations");
    }

    @Test
    void theNewForeignKeyIndexesServeTheirQueriesOnRealisticData() {
        Map<String, String> plans = tx.execute(s -> {
            s.setRollbackOnly();
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO medical_record_addenda (medical_record_id, content, created_by)
                    SELECT ? + g % ?, 'IT-FKSCAN', 1 FROM generate_series(1, ?) g""", BASE, PARENTS, ROWS);
            jdbc.update("""
                    INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                            is_external_purchase, order_line_id, external_reason)
                    SELECT ? + g % ?, 1, 1, 'IT-FKSCAN', true, NULL, 'OUT_OF_STOCK_AT_PRESCRIBE'
                    FROM generate_series(1, ?) g""", BASE, PARENTS, ROWS);
            // 50 loại vaccine dùng nhiều + đúng một mũi của loại hiếm BASE: hình dạng "loại chưa từng dùng thì xóa được".
            jdbc.update("""
                    INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                            dose_number, product_id, stock_lot_id, order_line_id, next_due_date, recorded_by)
                    SELECT 1, CASE WHEN g = 1 THEN ? ELSE 1 + g % 50 END, ? + g % ?, 1, DATE '2000-01-01', 1, 1, 1, 1,
                           -g, DATE '2000-02-01', 1
                    FROM generate_series(1, ?) g""", BASE, BASE, PARENTS, ROWS);
            jdbc.execute("ANALYZE medical_record_addenda");
            jdbc.execute("ANALYZE prescription_items");
            jdbc.execute("ANALYZE vaccinations");

            Map<String, String> result = new LinkedHashMap<>();
            result.put("addenda", explain("SELECT * FROM medical_record_addenda WHERE medical_record_id = ? ORDER BY id",
                    BASE + 7));
            result.put("prescription", explain("SELECT * FROM prescription_items WHERE medical_record_id = ? ORDER BY id",
                    BASE + 7));
            result.put("visit", explain("SELECT EXISTS (SELECT 1 FROM vaccinations WHERE visit_id = ?)", BASE + 7));
            result.put("type", explain("SELECT EXISTS (SELECT 1 FROM vaccinations WHERE vaccine_type_id = ?)", BASE));
            return result;
        });

        assertThat(plans.get("addenda")).contains("ix_medical_record_addenda_medical_record_id").doesNotContain("Seq Scan");
        assertThat(plans.get("prescription")).contains("ix_prescription_items_medical_record_id")
                .doesNotContain("Seq Scan");
        assertThat(plans.get("visit")).contains("ix_vaccinations_visit_id").doesNotContain("Seq Scan");
        assertThat(plans.get("type")).contains("ix_vaccinations_vaccine_type_id").doesNotContain("Seq Scan");
    }

    private String explain(String sql, Object... args) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
    }
}
