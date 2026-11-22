package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;
import com.petcare.module.inpatient.entity.Appetite;
import com.petcare.module.inpatient.fsm.AdmissionOrderTransitionHandler;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.sequence.DocumentCodeGenerator;
import com.petcare.support.MutableClock;

/**
 * FSM #25 "Y lệnh nội trú" trên bean thật (docs/09 §C): mọi cạnh hợp lệ đi qua handler của context rồi method entity và
 * DB chấp nhận dòng kết quả ({@code ck_admission_orders_done_fields}, {@code _cancelled_reason},
 * {@code _medication_item} của V38); mọi cặp không hợp lệ bị handler từ chối và dòng không đổi. Kèm bảng LOG
 * {@code admission_observations}: entity chỉ-thêm được DB chấp nhận. Bảng #25 <b>không</b> có cạnh X → X.
 * <p>
 * Era <b>năm 2094</b> (không job quét bảng nội trú → era tương lai, docs/architecture/testing-notes.md).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionOrderFsmIT.TestBeans.class})
class AdmissionOrderFsmIT {

    private static final Instant NOW = Instant.parse("2094-03-14T02:00:00Z");
    private static final String PERIOD = "9403";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AdmissionRepository admissions;
    @Autowired
    private AdmissionOrderRepository orders;
    @Autowired
    private AdmissionObservationRepository observations;
    @Autowired
    private AdmissionOrderTransitionHandler handler;
    @Autowired
    private DocumentCodeGenerator codes;
    @Autowired
    private MutableClock clock;

    private InpatientItSupport data;
    private TransactionTemplate tx;
    private long vet;
    private long caretaker;
    private long visit;
    private long admissionId;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new InpatientItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        long branch = data.branch();
        vet = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        long customer = data.customer();
        long pet = data.pet(customer);
        long service = data.inpatientService(200_000L);
        visit = data.visit(branch, customer, pet, vet);
        long kennel = data.kennel(branch);
        admissionId = tx.execute(status -> admissions.saveAndFlush(Admission.admit(
                codes.next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.now(clock)), visit, pet, branch,
                null, kennel, service, 200_000L, vet, clock.instant())).getId());
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
        jdbc.update("DELETE FROM document_counters WHERE doc_type = ? AND period = ?", Admission.CODE_DOC_TYPE,
                PERIOD);
    }

    /** Y lệnh#1 bằng entity thật, rồi trả id; {@code MEDICATION} dùng một dòng đơn thuốc của Visit. */
    private long order(AdmissionOrderType type) {
        Long item = type == AdmissionOrderType.MEDICATION ? data.prescriptionItem(visit) : null;
        return tx.execute(status -> {
            Admission admission = admissions.findByIdForUpdate(admissionId).orElseThrow();
            return orders.saveAndFlush(AdmissionOrder.order(admission, type, "Nội dung y lệnh", clock.instant(), item,
                    vet)).getId();
        });
    }

    @Test
    void initialStateIsOrderedAndEveryTypeIsAcceptedByDatabase() {
        handler.validateInitial(AdmissionOrderStatus.ORDERED);

        for (AdmissionOrderType type : AdmissionOrderType.values()) {
            long id = order(type);

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admission_orders WHERE id = ?", id);
            assertThat(row.get("status")).as(type.name()).isEqualTo("ORDERED");
            assertThat(row.get("order_type")).isEqualTo(type.name());
            assertThat(row.get("admission_id")).isEqualTo(admissionId);
            assertThat(row.get("created_by")).isEqualTo(vet);
            assertThat(row.get("done_by")).isNull();
            assertThat(row.get("cancel_reason")).isNull();
            assertThat(row.get("prescription_item_id") != null).as(type.name())
                    .isEqualTo(type == AdmissionOrderType.MEDICATION);
        }
    }

    @Test
    void manyOrdersMayShareOnePrescriptionItem() {
        long item = data.prescriptionItem(visit);

        List<Long> ids = tx.execute(status -> {
            Admission admission = admissions.findByIdForUpdate(admissionId).orElseThrow();
            return List.of(
                    orders.saveAndFlush(AdmissionOrder.order(admission, AdmissionOrderType.MEDICATION, "Sáng",
                            clock.instant(), item, vet)).getId(),
                    orders.saveAndFlush(AdmissionOrder.order(admission, AdmissionOrderType.MEDICATION, "Tối",
                            clock.instant().plusSeconds(43_200), item, vet)).getId());
        });

        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void completeEdgeIsAcceptedByHandlerAndByDatabase() {
        long id = order(AdmissionOrderType.FEEDING);
        clock.set(NOW.plusSeconds(1800));

        tx.executeWithoutResult(status -> {
            AdmissionOrder loaded = orders.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), AdmissionOrderStatus.DONE);
            loaded.complete(caretaker, clock.instant(), "Ăn hết khẩu phần");
            orders.saveAndFlush(loaded);
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admission_orders WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(row.get("done_by")).isEqualTo(caretaker);
        assertThat(row.get("done_at")).isNotNull();
        assertThat(row.get("done_note")).isEqualTo("Ăn hết khẩu phần");
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(row.get("content")).isEqualTo("Nội dung y lệnh");
    }

    @Test
    void cancelEdgeIsAcceptedByHandlerAndByDatabase() {
        long id = order(AdmissionOrderType.PROCEDURE);

        tx.executeWithoutResult(status -> {
            AdmissionOrder loaded = orders.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), AdmissionOrderStatus.CANCELLED);
            loaded.cancel("Thú đã ổn, không cần nữa");
            orders.saveAndFlush(loaded);
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admission_orders WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancel_reason")).isEqualTo("Thú đã ổn, không cần nữa");
        assertThat(row.get("done_by")).isNull();
        assertThat(row.get("done_at")).isNull();
        assertThat(row.get("done_note")).isNull();
    }

    /** Mọi cặp trừ hai cạnh hợp lệ ORDERED → DONE, ORDERED → CANCELLED (gồm cả ORDERED → ORDERED). */
    private static Stream<Arguments> invalidPairs() {
        return Stream.of(AdmissionOrderStatus.values())
                .flatMap(from -> Stream.of(AdmissionOrderStatus.values()).map(to -> Arguments.of(from, to)))
                .filter(pair -> !(pair.get()[0] == AdmissionOrderStatus.ORDERED
                        && pair.get()[1] != AdmissionOrderStatus.ORDERED));
    }

    @ParameterizedTest(name = "{0} → {1} bị từ chối")
    @MethodSource("invalidPairs")
    void invalidPairIsRejectedAndRowIsUntouched(AdmissionOrderStatus from, AdmissionOrderStatus to) {
        long id = order(AdmissionOrderType.FEEDING);
        if (from == AdmissionOrderStatus.DONE) {
            tx.executeWithoutResult(status -> {
                AdmissionOrder loaded = orders.findByIdForUpdate(id).orElseThrow();
                loaded.complete(vet, clock.instant(), "Xong");
                orders.saveAndFlush(loaded);
            });
        } else if (from == AdmissionOrderStatus.CANCELLED) {
            tx.executeWithoutResult(status -> {
                AdmissionOrder loaded = orders.findByIdForUpdate(id).orElseThrow();
                loaded.cancel("Hủy");
                orders.saveAndFlush(loaded);
            });
        }
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM admission_orders WHERE id = ?", id);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            AdmissionOrder loaded = orders.findByIdForUpdate(id).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(from);
            handler.validateTransition(loaded.getStatus(), to);
        })).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM admission_orders WHERE id = ?", id)).isEqualTo(before);
    }

    @Test
    void theDatabaseRefusesIncoherentOrderRows() {
        long id = order(AdmissionOrderType.FEEDING);

        // DONE thiếu cột done_*
        assertThatThrownBy(() -> jdbc.update("UPDATE admission_orders SET status = 'DONE' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // CANCELLED thiếu lý do
        assertThatThrownBy(() -> jdbc.update("UPDATE admission_orders SET status = 'CANCELLED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // MEDICATION thiếu dòng đơn thuốc
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO admission_orders (admission_id, order_type, content, scheduled_at, status, created_by)
                VALUES (?, 'MEDICATION', 'Thuốc', now(), 'ORDERED', ?)""", admissionId, vet))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM admission_orders WHERE id = ?", String.class, id))
                .isEqualTo("ORDERED");
    }

    @Test
    void observationIsAcceptedByDatabaseAndKeepsRoundedValues() {
        Long id = tx.execute(status -> {
            Admission admission = admissions.findByIdForUpdate(admissionId).orElseThrow();
            return observations.saveAndFlush(AdmissionObservation.record(admission, caretaker,
                    new java.math.BigDecimal("38.56"), 120, 30, new java.math.BigDecimal("5.555"), Appetite.REDUCED,
                    "Ăn ít, thở nhanh", true)).getId();
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admission_observations WHERE id = ?", id);
        assertThat(row.get("admission_id")).isEqualTo(admissionId);
        assertThat(row.get("recorded_by")).isEqualTo(caretaker);
        assertThat(row.get("temperature_c")).isEqualTo(new java.math.BigDecimal("38.6"));
        assertThat(((Number) row.get("heart_rate")).intValue()).isEqualTo(120);
        assertThat(((Number) row.get("respiratory_rate")).intValue()).isEqualTo(30);
        assertThat(row.get("weight_kg")).isEqualTo(new java.math.BigDecimal("5.56"));
        assertThat(row.get("appetite")).isEqualTo("REDUCED");
        assertThat(row.get("note")).isEqualTo("Ăn ít, thở nhanh");
        assertThat(row.get("abnormal")).isEqualTo(true);
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void lockMethodRequiresAnOpenTransaction() {
        long id = order(AdmissionOrderType.FEEDING);

        assertThatThrownBy(() -> orders.findByIdForUpdate(id)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
