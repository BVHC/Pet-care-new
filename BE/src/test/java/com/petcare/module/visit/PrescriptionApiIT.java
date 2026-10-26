package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.support.MutableClock;

/**
 * {@link PrescriptionApi#markExternalPurchaseAtPayment} trên Postgres thật (docs/adr/0076, T38 phần 0): đổi đúng ba cột
 * và {@code updated_at} theo clock, không đổi nội dung dòng, gỡ {@code order_line_id} nên sales xóa được dòng Order (FK
 * {@code RESTRICT}), chạy được trên lượt đã {@code COMPLETED} (ngoại lệ duy nhất của bệnh án đã khóa, BR-BH-04), dòng khác
 * không bị chạm, không phải dòng đơn thuốc / gọi lần hai thì lỗi mà không đổi gì, và đi theo transaction của bên gọi.
 * Đồng hồ {@link MutableClock} ở <b>năm 2016</b>.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PrescriptionApiIT.TestBeans.class})
class PrescriptionApiIT {

    private static final Instant T0 = Instant.parse("2016-05-10T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2016, 5, 10);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private PrescriptionApi api;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private VaccinationItSupport rx;
    private TransactionTemplate tx;
    private long vet;
    private long visit;
    private long drug;
    private long line;
    private long item;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        rx = new VaccinationItSupport(jdbc);
        long branch = data.branch("ACTIVE");
        long medical = data.service("MEDICAL");
        long receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        long customer = data.counterCustomer();
        long pet = data.pet(customer);
        long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0),
                "CHECKED_IN", receptionist);
        visit = data.visit(appointment, receptionist);
        drug = rx.prescriptionDrug("Amoxicillin");
        line = rx.drugLine(visit, drug, 3, vet);
        item = rx.prescriptionItem(visit, drug, 3, "Ngày 2 lần, mỗi lần 1 viên", line);
    }

    @AfterEach
    void tearDown() {
        rx.deleteDoses();
        rx.deleteCatalog();
        // cleanUp xóa visits theo lịch hẹn trước khi xóa bệnh án: bệnh án chèn ở IT này phải xóa trước
        jdbc.update("DELETE FROM medical_records WHERE visit_id = ?", visit);
        data.cleanUp();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM prescription_items WHERE id = ?", id);
    }

    private String xmin(long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM prescription_items WHERE id = ?", String.class, id);
    }

    @Test
    void movesTheLineToAnExternalPurchaseKeepsItsContentAndLetsSalesDeleteTheOrderLine() {
        Integer deleted = tx.execute(s -> {
            api.markExternalPurchaseAtPayment(line, vet);
            // sales xóa dòng Order sau khi gọi: FK RESTRICT đòi order_line_id đã được gỡ
            return jdbc.update("DELETE FROM order_lines WHERE id = ?", line);
        });

        assertThat(deleted).isEqualTo(1);
        Map<String, Object> after = row(item);
        assertThat(after).containsEntry("is_external_purchase", true)
                .containsEntry("external_reason", "OUT_OF_STOCK_AT_PAYMENT").containsEntry("product_id", drug)
                .containsEntry("quantity", 3).containsEntry("dosage_instructions", "Ngày 2 lần, mỗi lần 1 viên");
        assertThat(after.get("order_line_id")).as("dòng vẫn in trên đơn nhưng không còn dòng Order").isNull();
        assertThat(((Timestamp) after.get("updated_at")).toInstant()).isEqualTo(T0);
        assertThat(count("SELECT count(*) FROM prescription_items WHERE id = " + item)).isEqualTo(1);
    }

    @Test
    void worksOnALockedRecordOfACompletedVisitBecauseBrBh04AllowsItAfterTheRecordIsLocked() {
        jdbc.update("""
                UPDATE visits SET status = 'COMPLETED', assignee_id = ?, completed_at = now() WHERE id = ?""", vet, visit);
        jdbc.update("UPDATE medical_records SET locked_at = now() WHERE visit_id = ?", visit);

        tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(line, vet));

        assertThat(row(item)).containsEntry("is_external_purchase", true);
    }

    @Test
    void onlyTheMatchingItemChangesAndTheOtherItemsOfTheSameRecordStayUntouched() {
        long otherLine = rx.drugLine(visit, drug, 1, vet);
        long other = rx.prescriptionItem(visit, drug, 1, "Ngày 1 lần", otherLine);
        long external = rx.prescriptionItem(visit, drug, 2, "Ngày 3 lần", null);
        String otherXmin = xmin(other);
        String externalXmin = xmin(external);

        tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(line, vet));

        assertThat(row(item)).containsEntry("is_external_purchase", true);
        assertThat(xmin(other)).as("dòng khác của cùng bệnh án không bị ghi").isEqualTo(otherXmin);
        assertThat(row(other)).containsEntry("is_external_purchase", false).containsEntry("order_line_id", otherLine);
        assertThat(xmin(external)).as("dòng đã mua ngoài từ lúc kê không bị ghi").isEqualTo(externalXmin);
        assertThat(row(external)).containsEntry("external_reason", "OUT_OF_STOCK_AT_PRESCRIBE");
    }

    @Test
    void aLineThatIsNotAPrescriptionLineFailsAndChangesNothing() {
        long retailLine = rx.drugLine(visit, drug, 1, vet);
        String before = xmin(item);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(retailLine, vet)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#" + retailLine);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(Long.MAX_VALUE, vet)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(xmin(item)).isEqualTo(before);
        assertThat(row(item)).containsEntry("is_external_purchase", false).containsEntry("order_line_id", line);
    }

    @Test
    void callingTwiceFailsTheSecondTimeAndKeepsTheFirstResult() {
        tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(line, vet));
        String afterFirst = xmin(item);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.markExternalPurchaseAtPayment(line, vet)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(xmin(item)).isEqualTo(afterFirst);
        assertThat(row(item)).containsEntry("is_external_purchase", true)
                .containsEntry("external_reason", "OUT_OF_STOCK_AT_PAYMENT");
    }

    @Test
    void outsideATransactionItIsRefusedAndWritesNothing() {
        String before = xmin(item);

        assertThatThrownBy(() -> api.markExternalPurchaseAtPayment(line, vet))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(xmin(item)).isEqualTo(before);
    }

    @Test
    void itRollsBackWithTheCallersTransaction() {
        String before = xmin(item);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            api.markExternalPurchaseAtPayment(line, vet);
            throw new IllegalStateException("sales thất bại sau khi gọi");
        })).hasMessage("sales thất bại sau khi gọi");

        assertThat(xmin(item)).isEqualTo(before);
        assertThat(row(item)).containsEntry("is_external_purchase", false).containsEntry("order_line_id", line);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
