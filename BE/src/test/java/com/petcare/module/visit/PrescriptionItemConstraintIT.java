package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;

/**
 * Ràng buộc của {@code prescription_items} do DB giữ (docs/adr/0076, bảng C1–C4): số lượng dương, liều bắt buộc và ≤ 500,
 * không tách một dòng thành hai phần ({@code is_external_purchase} ⇔ không có {@code order_line_id}, BR-KB-03), lý do mua
 * ngoài chỉ nhận hai giá trị, mỗi dòng Order chỉ gắn một dòng đơn thuốc. Mỗi vi phạm là một ca, chèn trong transaction
 * rollback. Ca cuối ghi lại khoảng trống đã biết của V1 (nợ D043): DB chưa đòi lý do khi mua ngoài.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PrescriptionItemConstraintIT {

    private static final String INSERT = """
            INSERT INTO prescription_items (medical_record_id, product_id, quantity, dosage_instructions,
                    is_external_purchase, order_line_id, external_reason) VALUES (?, ?, ?, ?, ?, ?, ?)""";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private VaccinationItSupport rx;
    private TransactionTemplate tx;
    private long vet;
    private long visit;
    private long drug;
    private long freeLine;
    private long takenLine;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        rx = new VaccinationItSupport(jdbc);
        long branch = data.branch("ACTIVE");
        long medical = data.service("MEDICAL");
        long receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        long customer = data.counterCustomer();
        long pet = data.pet(customer);
        long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2016, 6, 10),
                LocalTime.of(9, 0), "CHECKED_IN", receptionist);
        visit = data.visit(appointment, receptionist);
        drug = rx.prescriptionDrug("Amoxicillin");
        rx.ensureRecord(visit);
        freeLine = rx.drugLine(visit, drug, 1, vet);
        takenLine = rx.drugLine(visit, drug, 1, vet);
        rx.prescriptionItem(visit, drug, 1, "Ngày 1 lần", takenLine);
    }

    @AfterEach
    void tearDown() {
        rx.deleteDoses();
        rx.deleteCatalog();
        // cleanUp xóa visits theo lịch hẹn trước khi xóa bệnh án: bệnh án chèn ở IT này phải xóa trước
        jdbc.update("DELETE FROM medical_records WHERE visit_id = ?", visit);
        data.cleanUp();
    }

    private void insert(int quantity, String dosage, boolean external, Long orderLine, String reason) {
        tx.executeWithoutResult(s -> jdbc.update(INSERT, visit, drug, quantity, dosage, external, orderLine, reason));
    }

    @Test
    void aValidItemWithAnOrderLineIsAccepted() {
        tx.executeWithoutResult(s -> {
            jdbc.update(INSERT, visit, drug, 2, "Ngày 2 lần", false, freeLine, null);
            s.setRollbackOnly();
        });
    }

    @Test
    void aValidExternalPurchaseWithAReasonIsAccepted() {
        tx.executeWithoutResult(s -> {
            jdbc.update(INSERT, visit, drug, 2, "Ngày 2 lần", true, null, "OUT_OF_STOCK_AT_PRESCRIBE");
            jdbc.update(INSERT, visit, drug, 2, "Ngày 2 lần", true, null, "OUT_OF_STOCK_AT_PAYMENT");
            s.setRollbackOnly();
        });
    }

    @Test
    void quantityMustBePositive() {
        assertThatThrownBy(() -> insert(0, "Ngày 2 lần", false, freeLine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(-1, "Ngày 2 lần", false, freeLine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dosageIsRequiredAndAtMost500Characters() {
        assertThatThrownBy(() -> insert(1, null, false, freeLine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(1, "x".repeat(501), false, freeLine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        tx.executeWithoutResult(s -> {
            jdbc.update(INSERT, visit, drug, 1, "x".repeat(500), false, freeLine, null);
            s.setRollbackOnly();
        });
    }

    @Test
    void aLineCannotBeSplitIntoAnOrderPartAndAnExternalPart() {
        assertThatThrownBy(() -> insert(1, "Ngày 2 lần", true, freeLine, "OUT_OF_STOCK_AT_PRESCRIBE"))
                .as("mua ngoài mà vẫn có dòng Order").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(1, "Ngày 2 lần", false, null, null))
                .as("không mua ngoài mà không có dòng Order").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theReasonOfAnExternalPurchaseAcceptsOnlyTheTwoDocumentedValues() {
        assertThatThrownBy(() -> insert(1, "Ngày 2 lần", true, null, "OUT_OF_STOCK"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(1, "Ngày 2 lần", true, null, ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOrderLineBacksAtMostOnePrescriptionItem() {
        assertThatThrownBy(() -> insert(1, "Ngày 2 lần", false, takenLine, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anItemNeedsAnExistingMedicalRecordAndProduct() {
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> jdbc.update(INSERT, Long.MAX_VALUE, drug, 1, "Ngày 2 lần", false, freeLine, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> jdbc.update(INSERT, visit, Long.MAX_VALUE, 1, "Ngày 2 lần", false, freeLine, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Khoảng trống đã biết (nợ D043, docs/adr/0076 C3): CHECK của V1 chỉ giới hạn giá trị nên lý do NULL vẫn lọt, kể cả khi
     * mua ngoài hay có dòng Order kèm lý do. Ứng dụng giữ (UPDATE đặt cả ba cột, factory ở phần 1). Khi V22 thêm CHECK
     * {@code is_external_purchase = (external_reason IS NOT NULL)} ca này phải đỏ và được đổi thành ca bị từ chối.
     */
    @Test
    void knownGapD043TheDatabaseDoesNotYetTieTheReasonToTheExternalFlag() {
        tx.executeWithoutResult(s -> {
            jdbc.update(INSERT, visit, drug, 1, "Ngày 2 lần", true, null, null);
            jdbc.update(INSERT, visit, drug, 1, "Ngày 2 lần", false, freeLine, "OUT_OF_STOCK_AT_PAYMENT");
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription_items WHERE medical_record_id = ?",
                Long.class, visit)).as("chỉ còn dòng dựng sẵn, hai dòng thử đã rollback").isEqualTo(1);
    }
}
