package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.petcare.module.appointment.AppointmentItSupport;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL khi đọc đơn thuốc của bệnh án (docs/adr/0076, khuôn {@code VisitQueryCountIT}): đúng 2 câu — đọc dòng đơn
 * thuốc, tra tên thuốc theo lô — với 1 và với 20 dòng thuốc khác nhau, và đúng 1 câu (không chạm catalog) khi bệnh án chưa
 * có đơn. Mutation "tra tên từng dòng" → 21 câu → đỏ. Context riêng bật Hibernate Statistics.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PrescriptionReadCountIT {

    @Autowired
    private PrescriptionItemReader reader;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private Statistics statistics;
    private long visit;
    private Long category;
    private final List<Long> products = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        data = new AppointmentItSupport(jdbc);
        long branch = data.branch("ACTIVE");
        long medical = data.service("MEDICAL");
        long receptionist = data.staff("RECEPTIONIST", branch);
        long customer = data.counterCustomer();
        long pet = data.pet(customer);
        long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2016, 7, 10),
                LocalTime.of(9, 0), "CHECKED_IN", receptionist);
        visit = data.visit(appointment, receptionist);
        category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                "Rx count IT " + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM prescription_items WHERE medical_record_id = ?", visit);
        for (long id : products) {
            jdbc.update("DELETE FROM products WHERE id = ?", id);
        }
        products.clear();
        // cleanUp xóa visits theo lịch hẹn trước khi xóa bệnh án: bệnh án chèn ở IT này phải xóa trước
        jdbc.update("DELETE FROM medical_records WHERE visit_id = ?", visit);
        data.cleanUp();
        jdbc.update("DELETE FROM product_categories WHERE id = ?", category);
    }

    /** Số câu SQL Hibernate chuẩn bị trong một transaction chạy {@code work}. */
    private long statements(Supplier<?> work) {
        return tx.execute(s -> {
            statistics.clear();
            work.get();
            return statistics.getPrepareStatementCount();
        });
    }

    /** Thêm {@code count} dòng đơn thuốc mua ngoài (không cần dòng Order), mỗi dòng một thuốc khác nhau. */
    private void prescribe(int count) {
        jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?) ON CONFLICT (visit_id) DO NOTHING", visit);
        for (int i = 0; i < count; i++) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            long product = jdbc.queryForObject("""
                    INSERT INTO products (category_id, sku, name, product_type, is_prescription, tracks_expiry, unit, price)
                    VALUES (?, ?, ?, 'DRUG', true, true, 'viên', 30000) RETURNING id""", Long.class, category,
                    "RXC-" + suffix, "Thuốc " + suffix);
            products.add(product);
            jdbc.update("""
                    INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                            is_external_purchase, external_reason)
                    VALUES (?, ?, 1, 'Ngày 2 lần', true, 'OUT_OF_STOCK_AT_PRESCRIBE')""", visit, product);
        }
    }

    @Test
    void readingThePrescriptionIsTwoStatementsForOneAndForTwentyItems() {
        prescribe(1);
        assertThat(reader.forRecord(visit)).hasSize(1);
        assertThat(statements(() -> reader.forRecord(visit))).isEqualTo(2);

        prescribe(19);
        assertThat(reader.forRecord(visit)).hasSize(20);
        assertThat(statements(() -> reader.forRecord(visit))).isEqualTo(2);
    }

    @Test
    void aRecordWithoutPrescriptionIsOneStatementAndNeverReachesTheCatalog() {
        jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?)", visit);

        assertThat(statements(() -> reader.forRecord(visit))).isEqualTo(1);
    }

    @Test
    void theItemsComeBackInTheOrderTheyWerePrescribedWithTheirOwnProductNames() {
        prescribe(5);

        List<String> names = reader.forRecord(visit).stream().map(i -> i.productName()).toList();
        List<Long> ids = reader.forRecord(visit).stream().map(i -> i.itemId()).toList();

        assertThat(ids).isSorted();
        assertThat(names).doesNotHaveDuplicates().hasSize(5).allMatch(n -> n.startsWith("Thuốc "));
    }
}
