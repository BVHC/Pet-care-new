package com.petcare.module.inpatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
import com.petcare.module.inpatient.api.AdmissionQueryApi;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.entity.DischargeOutcome;
import com.petcare.module.inpatient.fsm.AdmissionTransitionHandler;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.sequence.DocumentCodeGenerator;
import com.petcare.support.MutableClock;

/**
 * FSM #24 "Nhập viện" trên bean thật (docs/09 §C): mọi cạnh hợp lệ của bảng đi qua handler của context rồi method entity
 * và DB chấp nhận dòng kết quả ({@code ck_admissions_discharge_*} của V38, {@code ck_admissions_source} của V62); mọi
 * cặp không hợp lệ bị handler từ chối và dòng không đổi. Cũng chứng minh mã ca {@code NT-yyMM-xxxxx} sinh bằng
 * {@code DocumentCodeGenerator} và các ràng buộc DB mà entity không thay được ({@code ux_admissions_pet_admitted},
 * {@code ck_admissions_source}) và câu đọc {@code AdmissionQueryApi} (N2). Không đi qua endpoint vì chưa có (N3).
 * <p>
 * Era <b>năm 2093</b> (không job quét {@code admissions} → era tương lai, docs/architecture/testing-notes.md).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdmissionFsmIT.TestBeans.class})
class AdmissionFsmIT {

    private static final Instant NOW = Instant.parse("2093-03-14T02:00:00Z");
    private static final String PERIOD = "9303";

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
    private AdmissionTransitionHandler handler;
    @Autowired
    private DocumentCodeGenerator codes;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdmissionQueryApi query;

    private InpatientItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long vet;
    private long customer;
    private long service;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new InpatientItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        branch = data.branch();
        vet = data.staff("VET", branch);
        customer = data.customer();
        service = data.inpatientService(200_000L);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
        jdbc.update("DELETE FROM document_counters WHERE doc_type = ? AND period = ?", Admission.CODE_DOC_TYPE,
                PERIOD);
    }

    /** Một thú mới, Visit {@code IN_PROGRESS} của thú đó và id thú. */
    private long[] petAndVisit() {
        long pet = data.pet(customer);
        return new long[] {pet, data.visit(branch, customer, pet, vet)};
    }

    /** Nhập viện#1 bằng entity thật (đường a, chuồng điều trị), mã sinh từ {@code DocumentCodeGenerator}. */
    private long admitFromVisit(long pet, long visit) {
        long kennel = data.kennel(branch);
        return tx.execute(status -> admissions.saveAndFlush(Admission.admit(
                codes.next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.now(clock)), visit, pet, branch,
                null, kennel, service, 200_000L, vet, clock.instant())).getId());
    }

    @Test
    void initialStateIsAdmittedAndAdmitFromVisitIsAcceptedByDatabase() {
        long[] pv = petAndVisit();
        handler.validateInitial(AdmissionStatus.ADMITTED);

        long id = admitFromVisit(pv[0], pv[1]);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admissions WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("ADMITTED");
        assertThat((String) row.get("code")).matches("^NT-" + PERIOD + "-\\d{5}$");
        assertThat(row.get("kennel_id")).isNotNull();
        assertThat(row.get("boarding_booking_id")).isNull();
        assertThat(row.get("unit_price")).isEqualTo(200_000L);
        assertThat(row.get("admitted_by")).isEqualTo(vet);
        assertThat(row.get("discharge_outcome")).isNull();
        assertThat(row.get("order_line_id")).isNull();
    }

    @Test
    void admitFromBoardingIsAcceptedByDatabaseWithBookingAndNoKennel() {
        long[] pv = petAndVisit();
        long booking = data.checkedInBooking(branch, customer, pv[0], vet);

        long id = tx.execute(status -> admissions.saveAndFlush(Admission.admit(
                codes.next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.now(clock)), pv[1], pv[0], branch,
                booking, null, service, 200_000L, vet, clock.instant())).getId());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admissions WHERE id = ?", id);
        assertThat(row.get("boarding_booking_id")).isEqualTo(booking);
        assertThat(row.get("kennel_id")).isNull();
        assertThat(row.get("status")).isEqualTo("ADMITTED");
    }

    @Test
    void codesAreGapFreeAndSequentialWithinTheMonth() {
        long[] first = petAndVisit();
        long[] second = petAndVisit();

        long a = admitFromVisit(first[0], first[1]);
        long b = admitFromVisit(second[0], second[1]);

        int n1 = number(a);
        int n2 = number(b);
        assertThat(n2).isEqualTo(n1 + 1);
    }

    private int number(long admissionId) {
        String code = jdbc.queryForObject("SELECT code FROM admissions WHERE id = ?", String.class, admissionId);
        return Integer.parseInt(code.substring(code.lastIndexOf('-') + 1));
    }

    @Test
    void aRolledBackAdmissionGivesItsCodeBack() {
        long[] pv = petAndVisit();
        long kennel = data.kennel(branch);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            String code = codes.next(Admission.CODE_DOC_TYPE, Admission.CODE_PREFIX, LocalDate.now(clock));
            admissions.saveAndFlush(Admission.admit(code, pv[1], pv[0], branch, null, kennel, service, 1L, vet,
                    clock.instant()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admissions WHERE pet_id = ?", Integer.class, pv[0]))
                .isZero();
        assertThat(jdbc.queryForList("SELECT last_value FROM document_counters WHERE doc_type = ? AND period = ?",
                Integer.class, Admission.CODE_DOC_TYPE, PERIOD)).isEmpty();
    }

    @Test
    void dischargeEdgeIsAcceptedByHandlerAndByDatabase() {
        long[] pv = petAndVisit();
        long id = admitFromVisit(pv[0], pv[1]);
        long line = data.orderLine(pv[1], service, 1, 200_000L, vet);
        clock.set(NOW.plusSeconds(3 * 3600));

        tx.executeWithoutResult(status -> {
            Admission loaded = admissions.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), AdmissionStatus.DISCHARGED);
            loaded.discharge(DischargeOutcome.HOME, "Ổn định, về nhà", vet, clock.instant(), line, 0);
            admissions.saveAndFlush(loaded);
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admissions WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DISCHARGED");
        assertThat(row.get("discharge_outcome")).isEqualTo("HOME");
        assertThat(row.get("discharge_summary")).isEqualTo("Ổn định, về nhà");
        assertThat(row.get("discharged_by")).isEqualTo(vet);
        assertThat(row.get("discharged_at")).isNotNull();
        assertThat(row.get("order_line_id")).isEqualTo(line);
        // cột nhập viện không đổi
        assertThat(row.get("unit_price")).isEqualTo(200_000L);
        assertThat(row.get("admitted_by")).isEqualTo(vet);
    }

    private static Stream<Arguments> invalidPairs() {
        return Stream.of(AdmissionStatus.values())
                .flatMap(from -> Stream.of(AdmissionStatus.values()).map(to -> Arguments.of(from, to)))
                .filter(pair -> !(pair.get()[0] == AdmissionStatus.ADMITTED && pair.get()[1] == AdmissionStatus.DISCHARGED));
    }

    @ParameterizedTest(name = "{0} → {1} bị từ chối")
    @MethodSource("invalidPairs")
    void invalidPairIsRejectedAndRowIsUntouched(AdmissionStatus from, AdmissionStatus to) {
        long[] pv = petAndVisit();
        long id = admitFromVisit(pv[0], pv[1]);
        if (from == AdmissionStatus.DISCHARGED) {
            long line = data.orderLine(pv[1], service, 1, 200_000L, vet);
            tx.executeWithoutResult(status -> {
                Admission loaded = admissions.findByIdForUpdate(id).orElseThrow();
                loaded.discharge(DischargeOutcome.HOME, "Xong", vet, clock.instant(), line, 0);
                admissions.saveAndFlush(loaded);
            });
        }
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM admissions WHERE id = ?", id);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Admission loaded = admissions.findByIdForUpdate(id).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(from);
            handler.validateTransition(loaded.getStatus(), to);
        })).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM admissions WHERE id = ?", id)).isEqualTo(before);
    }

    @Test
    void anAdmissionWithBothSourcesOrNoSourceIsRejectedByTheDatabase() {
        long[] pv = petAndVisit();
        long booking = data.checkedInBooking(branch, customer, pv[0], vet);
        long kennel = data.kennel(branch);

        assertThatThrownBy(() -> insertRaw(pv, booking, kennel, "NT-RAW-1"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_admissions_source");
        assertThatThrownBy(() -> insertRaw(pv, null, null, "NT-RAW-2"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_admissions_source");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admissions WHERE pet_id = ?", Integer.class, pv[0]))
                .isZero();
    }

    private void insertRaw(long[] pv, Long booking, Long kennel, String code) {
        jdbc.update("""
                INSERT INTO admissions (code, visit_id, pet_id, branch_id, boarding_booking_id, kennel_id, service_id,
                        unit_price, status, admitted_by, admitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'ADMITTED', ?, now())""", code, pv[1], pv[0], branch, booking, kennel,
                service, vet);
    }

    @Test
    void aPetCannotHaveTwoAdmittedCasesButMayBeAdmittedAgainAfterDischarge() {
        long[] pv = petAndVisit();
        long first = admitFromVisit(pv[0], pv[1]);
        long secondVisit = data.visit(branch, customer, pv[0], vet);

        assertThatThrownBy(() -> admitFromVisit(pv[0], secondVisit))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_admissions_pet_admitted");

        long line = data.orderLine(pv[1], service, 1, 200_000L, vet);
        tx.executeWithoutResult(status -> {
            Admission loaded = admissions.findByIdForUpdate(first).orElseThrow();
            loaded.discharge(DischargeOutcome.HOME, "Xong", vet, clock.instant(), line, 0);
            admissions.saveAndFlush(loaded);
        });

        long again = admitFromVisit(pv[0], secondVisit);
        assertThat(again).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admissions WHERE pet_id = ? AND status = 'ADMITTED'",
                Integer.class, pv[0])).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesADischargedRowWithoutItsDischargeColumns() {
        long[] pv = petAndVisit();
        long id = admitFromVisit(pv[0], pv[1]);

        assertThatThrownBy(() -> jdbc.update("UPDATE admissions SET status = 'DISCHARGED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM admissions WHERE id = ?", String.class, id))
                .isEqualTo("ADMITTED");
    }

    /** {@code AdmissionQueryApi} (N2, docs/adr/0149 mục 13): chỉ ca {@code ADMITTED} được tính, theo thú và theo lượt. */
    @Test
    void activeAdmissionQueriesCountOnlyAdmittedCases() {
        long[] admitted = petAndVisit();
        long[] other = petAndVisit();
        long id = admitFromVisit(admitted[0], admitted[1]);

        assertThat(inTx(() -> query.hasActiveAdmission(admitted[0]))).isTrue();
        assertThat(inTx(() -> query.hasActiveAdmissionForVisit(admitted[1]))).isTrue();
        assertThat(inTx(() -> query.hasActiveAdmission(other[0]))).isFalse();
        assertThat(inTx(() -> query.hasActiveAdmissionForVisit(other[1]))).isFalse();

        long line = data.orderLine(admitted[1], service, 1, 200_000L, vet);
        tx.executeWithoutResult(status -> {
            Admission loaded = admissions.findByIdForUpdate(id).orElseThrow();
            loaded.discharge(DischargeOutcome.HOME, "Xong", vet, clock.instant(), line, 0);
            admissions.saveAndFlush(loaded);
        });

        assertThat(inTx(() -> query.hasActiveAdmission(admitted[0]))).isFalse();
        assertThat(inTx(() -> query.hasActiveAdmissionForVisit(admitted[1]))).isFalse();
    }

    private boolean inTx(BooleanSupplier call) {
        return Boolean.TRUE.equals(tx.execute(status -> call.getAsBoolean()));
    }

    @Test
    void lockMethodRequiresAnOpenTransaction() {
        long[] pv = petAndVisit();
        long id = admitFromVisit(pv[0], pv[1]);

        assertThatThrownBy(() -> admissions.findByIdForUpdate(id)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
