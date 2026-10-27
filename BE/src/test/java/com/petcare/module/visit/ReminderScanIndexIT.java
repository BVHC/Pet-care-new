package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.VaccinationRepository;

/**
 * Câu quét và câu khóa của ST04 (docs/adr/0082) dùng đúng index trên dữ liệu gần thật: 20 000 mũi tiêm / bệnh án chèn trong
 * transaction rollback (FK bỏ kiểm bằng {@code session_replication_role = replica}) + {@code ANALYZE}, khuôn
 * {@code NoShowIT.overdueQueriesUseTheirIndexesOnRealisticData}. Hình dạng như khi ST04 chạy đều: phần lớn mũi đã bị thay
 * thế, mọi mũi quá khứ đã nhắc, chỉ vài chục mũi trong cửa sổ 7 ngày còn chờ. Plan lấy từ chính chuỗi {@code @Query} của
 * repository nên đổi câu SQL mà quên index sẽ đỏ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReminderScanIndexIT {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void scanAndLockQueriesUseTheirIndexesOnRealisticData() {
        Map<String, String> plans = new TransactionTemplate(transactionManager).execute(s -> {
            s.setRollbackOnly();
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                            queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at, completed_at)
                    SELECT 'VS-EXPL-' || g, 1, 1, g, 'MEDICAL', 'COMPLETED', 'WALK_IN', now(), 1, 1, now(), now(), now()
                    FROM generate_series(1, 20000) g""");
            jdbc.update("""
                    INSERT INTO vaccinations (pet_id, vaccine_type_id, visit_id, branch_id, administered_on, protocol_id,
                            dose_number, product_id, stock_lot_id, order_line_id, next_due_date, due_reminded_at,
                            superseded_at, recorded_by)
                    SELECT v.pet_id, 1, v.id, 1, DATE '2025-01-01' + (v.pet_id % 730)::int - 365, 1, 1, 1, 1, 900000000 + v.id,
                           DATE '2025-01-01' + (v.pet_id % 730)::int,
                           CASE WHEN DATE '2025-01-01' + (v.pet_id % 730)::int < DATE '2026-06-01' THEN now() END,
                           CASE WHEN v.pet_id % 5 <> 0 THEN now() END, 1
                    FROM visits v WHERE v.code LIKE 'VS-EXPL-%'""");
            jdbc.update("""
                    INSERT INTO medical_records (visit_id, follow_up_date, follow_up_reminded_at, locked_at)
                    SELECT v.id, CASE WHEN v.pet_id % 3 = 0 THEN DATE '2025-01-01' + (v.pet_id % 730)::int END,
                           CASE WHEN v.pet_id % 3 = 0 AND DATE '2025-01-01' + (v.pet_id % 730)::int < DATE '2026-06-01'
                                THEN now() END, now()
                    FROM visits v WHERE v.code LIKE 'VS-EXPL-%'""");
            jdbc.execute("ANALYZE visits");
            jdbc.execute("ANALYZE vaccinations");
            jdbc.execute("ANALYZE medical_records");

            LocalDate vaccineTo = FROM.plusDays(7);
            LocalDate followUpTo = FROM.plusDays(3);
            long dose = jdbc.queryForObject("""
                    SELECT min(id) FROM vaccinations WHERE next_due_date BETWEEN ? AND ? AND superseded_at IS NULL""",
                    Long.class, FROM, vaccineTo);
            long doseMiddle = jdbc.queryForObject("""
                    SELECT id FROM vaccinations WHERE next_due_date BETWEEN ? AND ? AND superseded_at IS NULL
                    ORDER BY next_due_date, id OFFSET 5 LIMIT 1""", Long.class, FROM, vaccineTo);
            long record = jdbc.queryForObject("""
                    SELECT min(visit_id) FROM medical_records WHERE follow_up_date BETWEEN ? AND ?
                      AND follow_up_reminded_at IS NULL""", Long.class, FROM, followUpTo);
            long recordMiddle = jdbc.queryForObject("""
                    SELECT visit_id FROM medical_records WHERE follow_up_date BETWEEN ? AND ?
                      AND follow_up_reminded_at IS NULL ORDER BY follow_up_date, visit_id OFFSET 2 LIMIT 1""",
                    Long.class, FROM, followUpTo);

            Map<String, String> result = new HashMap<>();
            result.put("dosesInWindow", jdbc.queryForObject("""
                    SELECT count(*) FROM vaccinations WHERE next_due_date BETWEEN ? AND ? AND superseded_at IS NULL
                      AND due_reminded_at IS NULL""", String.class, FROM, vaccineTo));
            result.put("recordsInWindow", jdbc.queryForObject("""
                    SELECT count(*) FROM medical_records WHERE follow_up_date BETWEEN ? AND ?
                      AND follow_up_reminded_at IS NULL""", String.class, FROM, followUpTo));
            result.put("vaccineScan", plan(VaccinationRepository.class, "findDueForReminderIds",
                    new Class<?>[] {LocalDate.class, LocalDate.class, int.class}, FROM, vaccineTo, 100));
            result.put("vaccineScanAfter", plan(VaccinationRepository.class, "findDueForReminderIdsAfter",
                    new Class<?>[] {LocalDate.class, LocalDate.class, long.class, int.class}, FROM, vaccineTo,
                    doseMiddle, 100));
            result.put("vaccineLock", plan(VaccinationRepository.class, "lockDueForReminder",
                    new Class<?>[] {long.class, LocalDate.class, LocalDate.class}, dose, FROM, vaccineTo));
            result.put("followUpScan", plan(MedicalRecordRepository.class, "findDueForReminderIds",
                    new Class<?>[] {LocalDate.class, LocalDate.class, int.class}, FROM, followUpTo, 100));
            result.put("followUpScanAfter", plan(MedicalRecordRepository.class, "findDueForReminderIdsAfter",
                    new Class<?>[] {LocalDate.class, LocalDate.class, long.class, int.class}, FROM, followUpTo,
                    recordMiddle, 100));
            result.put("followUpLock", plan(MedicalRecordRepository.class, "lockDueForReminder",
                    new Class<?>[] {long.class, LocalDate.class, LocalDate.class}, record, FROM, followUpTo));
            return result;
        });

        assertThat(Integer.parseInt(plans.get("dosesInWindow"))).as("dữ liệu đúng hình dạng: vài chục mũi chờ nhắc")
                .isBetween(10, 200);
        assertThat(Integer.parseInt(plans.get("recordsInWindow"))).isBetween(5, 100);
        assertThat(plans.get("vaccineScan")).doesNotContain("Seq Scan on vaccinations")
                .contains("ix_vaccinations_next_due");
        assertThat(plans.get("vaccineScanAfter")).as("lô sau: bảng ngoài theo index, mũi mốc theo khóa chính")
                .doesNotContain("Seq Scan on vaccinations").contains("ix_vaccinations_next_due")
                .contains("vaccinations_pkey");
        assertThat(plans.get("vaccineLock")).doesNotContain("Seq Scan on vaccinations")
                .contains("vaccinations_pkey");
        assertThat(plans.get("followUpScan")).doesNotContain("Seq Scan on medical_records")
                .contains("ix_medical_records_follow_up_due");
        assertThat(plans.get("followUpScanAfter")).doesNotContain("Seq Scan on medical_records")
                .contains("ix_medical_records_follow_up_due").contains("medical_records_pkey");
        assertThat(plans.get("followUpLock")).doesNotContain("Seq Scan on medical_records")
                .contains("medical_records_pkey");
    }

    /** EXPLAIN của chính chuỗi {@code @Query} (tham số tên → {@code ?} theo đúng thứ tự xuất hiện). */
    private String plan(Class<?> repository, String method, Class<?>[] types, Object... args) {
        try {
            Method m = repository.getMethod(method, types);
            String sql = m.getAnnotation(Query.class).value().replaceAll(":[a-zA-Z]+", "?");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
