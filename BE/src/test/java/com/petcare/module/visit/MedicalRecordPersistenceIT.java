package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.visit.entity.ExternalPurchaseReason;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.MedicalRecordAddendum;
import com.petcare.module.visit.entity.PrescriptionItem;
import com.petcare.module.visit.repository.MedicalRecordAddendumRepository;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.support.MutableClock;

/**
 * Nền bệnh án (T27 phần 0, docs/adr/0066) trên Postgres 17: entity khớp V1, PK gán tay chèn được qua {@code save}
 * (đường {@code merge}), {@code created_at} theo {@link MutableClock} ở <b>năm 2010</b> (chưa IT nào dùng); G1 mỗi lượt
 * tối đa một bệnh án; bản bổ sung đọc theo thứ tự ghi; {@code hasVaccinationInVisit}. Dữ liệu FK thật, xóa ở
 * {@link AppointmentItSupport#cleanUp()} (xóa bệnh án và bản bổ sung trước {@code visits}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MedicalRecordPersistenceIT.TestBeans.class})
class MedicalRecordPersistenceIT {

    private static final Instant T0 = Instant.parse("2010-04-12T02:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MedicalRecordRepository records;
    @Autowired
    private MedicalRecordAddendumRepository addenda;
    @Autowired
    private VisitRepository visits;
    @Autowired
    private PrescriptionItemRepository prescriptions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long receptionist;
    private long vet;
    private long customer;
    private long pet;
    private long visit;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        long branch = data.branch("ACTIVE");
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
        visit = visit(branch);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void recordWithAssignedKeyIsInsertedWithClockTimestamps() {
        tx.executeWithoutResult(s -> records.saveAndFlush(MedicalRecord.of(visit)));

        assertThat(jdbc.queryForObject("SELECT created_at = ? AND updated_at = ? FROM medical_records WHERE visit_id = ?",
                Boolean.class, Timestamp.from(T0), Timestamp.from(T0), visit)).isTrue();
        MedicalRecord read = tx.execute(s -> records.findById(visit).orElseThrow());
        assertThat(read.getDiagnosis()).isNull();
        assertThat(read.getLockedAt()).isNull();
    }

    /** G1 (erd §6): PK = {@code visit_id}. Mutation thêm {@code @GeneratedValue} → INSERT đầu đã lỗi → đỏ. */
    @Test
    void aVisitHasAtMostOneMedicalRecord() {
        tx.executeWithoutResult(s -> records.saveAndFlush(MedicalRecord.of(visit)));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO medical_records (visit_id) VALUES (?)", visit))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medical_records WHERE visit_id = ?", Long.class, visit))
                .isEqualTo(1L);
    }

    @Test
    void addendaAreReadInWritingOrderWithClockTimestamp() {
        tx.executeWithoutResult(s -> {
            records.saveAndFlush(MedicalRecord.of(visit));
            addenda.saveAndFlush(MedicalRecordAddendum.of(visit, "thứ nhất", false, vet));
            addenda.saveAndFlush(MedicalRecordAddendum.of(visit, "thứ hai", true, vet));
        });

        List<MedicalRecordAddendum> read = tx.execute(s -> addenda.findByMedicalRecordIdOrderByIdAsc(visit));
        assertThat(read)
                .extracting(MedicalRecordAddendum::getContent, MedicalRecordAddendum::isInternal,
                        MedicalRecordAddendum::getCreatedBy, MedicalRecordAddendum::getCreatedAt)
                .containsExactly(
                        tuple("thứ nhất", false, vet, T0),
                        tuple("thứ hai", true, vet, T0));
    }

    /** Lượt chưa có mũi nào → false; bản có mũi được kiểm ở T27 phần 3 (cần lô kho, dòng Order thật). */
    @Test
    void visitWithoutVaccinationHasNone() {
        assertThat(visits.hasVaccinationInVisit(pet, visit)).isFalse();
    }

    /** T38 phần 0 (docs/adr/0076, G7/G11): entity {@code PrescriptionItem} khớp V1 và đọc theo thứ tự kê, chỉ của bệnh án đó. */
    @Test
    void prescriptionItemsAreMappedFromTheirColumnsAndReadInPrescribingOrderPerRecord() {
        VaccinationItSupport rx = new VaccinationItSupport(jdbc);
        try {
            long product = rx.prescriptionDrug("Amoxicillin");
            long line = rx.drugLine(visit, product, 3, vet);
            long first = rx.prescriptionItem(visit, product, 3, "Ngày 2 lần", line);
            long second = rx.prescriptionItem(visit, product, 2, "Ngày 3 lần", null);
            long otherVisit = visit(jdbc.queryForObject("SELECT branch_id FROM visits WHERE id = ?", Long.class, visit));
            rx.prescriptionItem(otherVisit, product, 1, "Ngày 1 lần", null);

            List<PrescriptionItem> read = tx
                    .execute(s -> prescriptions.findByMedicalRecordIdOrderByIdAsc(visit));

            assertThat(read).extracting(PrescriptionItem::getId, PrescriptionItem::getMedicalRecordId,
                    PrescriptionItem::getProductId, PrescriptionItem::getQuantity,
                    PrescriptionItem::getDosageInstructions, PrescriptionItem::isExternalPurchase,
                    PrescriptionItem::getOrderLineId, PrescriptionItem::getExternalReason)
                    .containsExactly(
                            tuple(first, visit, product, 3, "Ngày 2 lần", false, line, null),
                            tuple(second, visit, product, 2, "Ngày 3 lần", true, null,
                                    ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE));
            List<PrescriptionItem> unknown = tx
                    .execute(s -> prescriptions.findByMedicalRecordIdOrderByIdAsc(Long.MAX_VALUE));
            assertThat(unknown).isEmpty();
        } finally {
            rx.deleteDoses();
            rx.deleteCatalog();
        }
    }

    private long visit(long branchId) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', 'IN_PROGRESS', 'WALK_IN', ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "TN-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customer, pet,
                Timestamp.from(T0), vet, receptionist, Timestamp.from(T0),
                Timestamp.from(T0));
    }
}
