package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #10 hoàn tất lượt (Visit#5) trên Postgres thật (docs/adr/0067). Mục theo plan:
 * <ul>
 * <li>Commit (C1): bốn bảng đổi cùng lúc, {@code locked_at = completed_at}, cờ cần gán lại gỡ, bệnh án khóa.</li>
 * <li>Rollback (R1–R5): trigger tạm chặn UPDATE lịch hẹn / Order / bệnh án, guard BR-KB-02 sau ba lệnh ghi, hoàn tất lại
 * được sau khi gỡ lỗi.</li>
 * <li>Xác thực / phân quyền / thứ tự lỗi; BR-KB-02 (Khám, Tiêm, A16); GROOMING.</li>
 * <li>Race ({@code pg_stat_activity}): đổi dòng dịch vụ trong lúc chờ khóa Order (D044), thêm dòng không bị ghi đè tổng
 * tiền (D045), ghi chẩn đoán xong trước khi hoàn tất.</li>
 * </ul>
 * {@code CashierShiftQueryApi} (placeholder, D016) được thay bằng mock "không có ca mở". Đồng hồ {@link MutableClock} ở
 * <b>năm 2012</b>; chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitCompleteIT.TestBeans.class})
class VisitCompleteIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2012, 4, 6);
    private static final Instant T0 = at(9, 0);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @MockitoBean
    private CashierShiftQueryApi cashierShifts;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> vaccineServices = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long vaccine;
    private long grooming;
    private long receptionist;
    private long vetA;
    private long vetB;
    private long caretaker;
    private long manager;
    private long customer;
    private long pet;
    private String receptionistToken;
    private String vetAToken;
    private String vetBToken;
    private String caretakerToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        vaccine = vaccineService();
        for (long b : List.of(branch, otherBranch)) {
            for (long service : List.of(medical, vaccine, grooming)) {
                data.branchService(b, service, true);
            }
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        customer = data.counterCustomer("Khách hoàn tất " + UUID.randomUUID(), null);
        pet = data.pet(customer);
        receptionistToken = token(receptionist);
        vetAToken = token(vetA);
        vetBToken = token(vetB);
        caretakerToken = token(caretaker);
        managerToken = token(manager);
    }

    @AfterEach
    void tearDown() {
        for (long b : branches) {
            jdbc.update("""
                    DELETE FROM medical_record_addenda WHERE medical_record_id IN (
                        SELECT id FROM visits WHERE branch_id = ?)""", b);
            jdbc.update("""
                    DELETE FROM medical_records WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)""", b);
            jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        data.cleanUp();
        for (long service : vaccineServices) {
            jdbc.update("DELETE FROM branch_services WHERE service_id = ?", service);
            jdbc.update("DELETE FROM services WHERE id = ?", service);
        }
    }

    // ================================================================ commit (C1)

    @Test
    void completingAnExamVisitWithAnAppointmentCommitsEveryTableAtOnce() {
        long visit = appointmentVisit(medical, vetA);
        long appointment = jdbc.queryForObject("SELECT appointment_id FROM visits WHERE id = ?", Long.class, visit);
        data(put(vetAToken, visit, Map.of("examination", "Khám", "diagnosis", "Viêm da")), HttpStatus.OK);
        jdbc.update("UPDATE visits SET needs_reassign = true WHERE id = ?", visit);
        Map<String, Object> before = data(getVisit(vetAToken, visit), HttpStatus.OK);

        Map<String, Object> done = data(complete(vetAToken, visit), HttpStatus.OK);

        Timestamp completedAt = jdbc.queryForObject("SELECT completed_at FROM visits WHERE id = ?", Timestamp.class, visit);
        Timestamp lockedAt = jdbc.queryForObject("SELECT locked_at FROM medical_records WHERE visit_id = ?",
                Timestamp.class, visit);
        assertThat(done).containsEntry("visitId", ((Number) visit).intValue()).containsEntry("status", "COMPLETED")
                .containsEntry("needsReassign", false).containsEntry("assigneeId", (int) vetA)
                .containsEntry("orderId", before.get("orderId")).containsEntry("calledAt", before.get("calledAt"))
                .containsEntry("cancelledAt", null).containsEntry("cancelReason", null);
        assertThat(Instant.parse((String) done.get("completedAt"))).isEqualTo(completedAt.toInstant())
                .isEqualTo(lockedAt.toInstant());
        assertThat(row("SELECT status, needs_reassign FROM visits WHERE id = " + visit))
                .containsEntry("status", "COMPLETED").containsEntry("needs_reassign", false);
        assertThat(row("SELECT status FROM appointments WHERE id = " + appointment)).containsEntry("status", "COMPLETED");
        assertThat(row("SELECT status, pending_at FROM orders WHERE visit_id = " + visit))
                .containsEntry("status", "PENDING").doesNotContainEntry("pending_at", null);

        Map<String, Object> record = data(get(vetBToken, visit), HttpStatus.OK);
        assertThat(record).containsEntry("locked", true).containsEntry("diagnosis", "Viêm da");
        assertError(put(vetAToken, visit, Map.of("diagnosis", "Sửa")), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(exchange(HttpMethod.POST, vetAToken, "/api/visits/%d/weight".formatted(visit),
                Map.of("weightKg", 5)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(complete(vetAToken, visit), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void aGroomingVisitIsCompletedByTheCaretakerWithoutAMedicalRecord() {
        long visit = called(walkIn(grooming), caretaker);

        Map<String, Object> done = data(complete(caretakerToken, visit), HttpStatus.OK);

        assertThat(done).containsEntry("status", "COMPLETED");
        assertThat(row("SELECT status FROM orders WHERE visit_id = " + visit)).containsEntry("status", "PENDING");
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit)).isZero();
    }

    // ================================================================ BR-KB-02 và rollback do guard (R4, R5)

    @Test
    void examWithoutADiagnosisIs400AfterThreeWritesAndRollsEverythingBackThenSucceedsOnceFixed() {
        long visit = appointmentVisit(medical, vetA);
        Map<String, Long> before = xmins(visit);

        ResponseEntity<Map<String, Object>> rejected = complete(vetAToken, visit);

        assertRule(rejected, "BR-KB-02");
        assertThat(xmins(visit)).as("không bảng nào bị ghi").isEqualTo(before);
        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");

        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        data(complete(vetAToken, visit), HttpStatus.OK);
    }

    @Test
    void anExamRecordWithOnlyBlankFieldsStillLacksADiagnosis() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("examination", "Chỉ khám")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        assertRule(complete(vetAToken, visit), "BR-KB-02");

        assertThat(xmins(visit)).isEqualTo(before);
    }

    @Test
    void aVaccineOnlyVisitWithoutADoseIsRejectedAndRolledBack() {
        long visit = called(walkIn(vaccine), vetA);
        Map<String, Long> before = xmins(visit);

        assertRule(complete(vetAToken, visit), "BR-KB-02");

        assertThat(xmins(visit)).isEqualTo(before);
    }

    /** A16: VET xóa dòng dịch vụ tự sinh → lượt Khám/Tiêm không còn dịch vụ nào → từ chối. */
    @Test
    void aMedicalVisitWhoseOrderHasNoServiceLineIsRejected() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        jdbc.update("DELETE FROM order_lines WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)", visit);
        jdbc.update("UPDATE orders SET total_amount = 0 WHERE visit_id = ?", visit);
        Map<String, Long> before = xmins(visit);

        assertRule(complete(vetAToken, visit), "BR-KB-02");

        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(row("SELECT status FROM orders WHERE visit_id = " + visit)).containsEntry("status", "OPEN");
    }

    // ================================================================ rollback tại từng điểm ghi (R1–R3, R5)

    @Test
    void aFailureWhileWritingTheAppointmentRollsTheVisitBack() {
        long visit = appointmentVisit(medical, vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        ResponseEntity<Map<String, Object>> failed = withRejectingTrigger("UPDATE", "appointments", "true",
                () -> complete(vetAToken, visit));

        assertError(failed, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");
        data(complete(vetAToken, visit), HttpStatus.OK);
    }

    @Test
    void aFailureWhileWritingTheOrderRollsTheVisitAndTheAppointmentBack() {
        long visit = appointmentVisit(medical, vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        ResponseEntity<Map<String, Object>> failed = withRejectingTrigger("UPDATE", "orders", "true",
                () -> complete(vetAToken, visit));

        assertError(failed, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(row("SELECT status FROM appointments WHERE id = (SELECT appointment_id FROM visits WHERE id = "
                + visit + ")")).containsEntry("status", "CHECKED_IN");
        data(complete(vetAToken, visit), HttpStatus.OK);
    }

    @Test
    void aFailureWhileLockingTheMedicalRecordRollsEveryEarlierWriteBack() {
        long visit = appointmentVisit(medical, vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        ResponseEntity<Map<String, Object>> failed = withRejectingTrigger("UPDATE", "medical_records", "true",
                () -> complete(vetAToken, visit));

        assertError(failed, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(row("SELECT status FROM orders WHERE visit_id = " + visit)).containsEntry("status", "OPEN");
        data(complete(vetAToken, visit), HttpStatus.OK);
    }

    // ================================================================ xác thực / phân quyền / thứ tự lỗi

    @Test
    void withoutTokenOrAfterLogoutIs401() {
        long visit = called(walkIn(medical), vetA);

        assertError(complete(null, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vetA);
        assertError(complete(vetAToken, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void aLockedAssigneeIs401AndNothingChanges() {
        long visit = called(walkIn(medical), vetA);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vetA);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vetA);

        assertError(complete(vetAToken, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void aVetWhoMustChangeThePasswordGetsBrTk17() {
        long visit = called(walkIn(medical), vetA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vetA);

        ResponseEntity<Map<String, Object>> response = complete(vetAToken, visit);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).contains("BR-TK-17");
    }

    @Test
    void rolesOutsideTheContractAre403() {
        long visit = called(walkIn(medical), vetA);

        for (String bearer : List.of(receptionistToken, managerToken)) {
            assertError(complete(bearer, visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void anotherVetOfTheBranchAndStaffOfAnotherBranchAre403() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        String otherVet = token(data.staff("VET", otherBranch));
        Map<String, Long> before = xmins(visit);

        assertScopeDenied(complete(vetBToken, visit));
        assertScopeDenied(complete(otherVet, visit));
        assertScopeDenied(complete(caretakerToken, visit));

        assertThat(xmins(visit)).isEqualTo(before);
    }

    @Test
    void aWaitingUnassignedVisitIs403BeforeTheStateCheck() {
        long visit = walkIn(medical);

        assertScopeDenied(complete(vetAToken, visit));
    }

    @Test
    void aFormerAssigneeIs403AfterTheVisitMovedToAnotherVet() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetB, visit);

        assertScopeDenied(complete(vetAToken, visit));
        data(complete(vetBToken, visit), HttpStatus.OK);
    }

    @Test
    void anAssigneeWhoseRoleNoLongerMatchesTheServiceGroupGetsBrTn05Before409() {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", vetA);
        Map<String, Long> before = xmins(visit);

        assertRule(complete(vetAToken, visit), "BR-TN-05");

        assertThat(xmins(visit)).isEqualTo(before);
    }

    @Test
    void missingVisitIs404AndMalformedIdIs400() {
        assertError(complete(vetAToken, 987654321L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(exchange(HttpMethod.POST, vetAToken, "/api/visits/abc/complete", null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(exchange(HttpMethod.POST, vetAToken, "/api/visits/1.5/complete", null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void aWaitingVisitOfTheAssigneeIs409() {
        long visit = assigned(walkIn(medical), vetA);
        Map<String, Long> before = xmins(visit);

        assertError(complete(vetAToken, visit), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(xmins(visit)).isEqualTo(before);
    }

    @Test
    void aBodySentWithTheRequestIsIgnored() {
        long visit = called(walkIn(grooming), caretaker);

        Map<String, Object> done = data(exchange(HttpMethod.POST, caretakerToken,
                "/api/visits/%d/complete".formatted(visit), Map.of("status", "CANCELLED", "completedAt", "2000-01-01")),
                HttpStatus.OK);

        assertThat(done).containsEntry("status", "COMPLETED");
    }

    // ================================================================ race

    /** D044: đổi dòng dịch vụ trong lúc hoàn tất chờ khóa Order → guard thấy dịch vụ mới, không phải bản cũ. */
    @Test
    void completionWaitsForTheOrderLockAndThenSeesTheNewServiceLine() throws Exception {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM orders WHERE visit_id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from orders%for no key update%",
                j -> j.update("""
                        UPDATE order_lines SET service_id = ?
                        WHERE order_id = (SELECT id FROM orders WHERE visit_id = ?)""", vaccine, visit),
                List.of(() -> complete(vetAToken, visit)), 1);

        assertRule(results.get(0), "BR-KB-02");
        Map<String, Long> after = xmins(visit);
        assertThat(after.get("visit")).isEqualTo(before.get("visit"));
        assertThat(row("SELECT status FROM visits WHERE id = " + visit)).containsEntry("status", "IN_PROGRESS");
        assertThat(row("SELECT status FROM orders WHERE visit_id = " + visit)).containsEntry("status", "OPEN");
    }

    /** D045: dòng thêm trong lúc chờ khóa Order phải có trong tổng tiền của Order {@code PENDING}. */
    @Test
    void completionDoesNotOverwriteTheTotalOfALineAddedWhileItWaited() throws Exception {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        long original = jdbc.queryForObject("SELECT total_amount FROM orders WHERE visit_id = ?", Long.class, visit);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM orders WHERE visit_id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from orders%for no key update%",
                j -> {
                    j.update("""
                            INSERT INTO order_lines (order_id, line_type, service_id, description, quantity, unit_price,
                                    line_total, added_by, owner_account_id)
                            SELECT o.id, 'SERVICE', ?, 'Thêm lúc chờ', 1, 150000, 150000, ?, ?
                            FROM orders o WHERE o.visit_id = ?""", medical, vetA, vetA, visit);
                    j.update("UPDATE orders SET total_amount = total_amount + 150000 WHERE visit_id = ?", visit);
                },
                List.of(() -> complete(vetAToken, visit)), 1);

        data(results.get(0), HttpStatus.OK);
        assertThat(row("SELECT status, total_amount FROM orders WHERE visit_id = " + visit))
                .containsEntry("status", "PENDING").containsEntry("total_amount", original + 150000);
        assertThat(count("SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = "
                + "(SELECT id FROM orders WHERE visit_id = " + visit + ")")).isEqualTo(original + 150000);
    }

    /** Ghi chẩn đoán (#13) commit trước khi hoàn tất lấy được khóa lượt → hoàn tất thấy chẩn đoán. */
    @Test
    void completionWaitsForTheVisitLockAndThenSeesTheDiagnosisWrittenMeanwhile() throws Exception {
        long visit = called(walkIn(medical), vetA);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from visits where id = % for no key update%",
                j -> j.update("INSERT INTO medical_records (visit_id, diagnosis, last_edited_by) VALUES (?, 'dx', ?)",
                        visit, vetA),
                List.of(() -> complete(vetAToken, visit)), 1);

        data(results.get(0), HttpStatus.OK);
        assertThat(row("SELECT diagnosis FROM medical_records WHERE visit_id = " + visit))
                .containsEntry("diagnosis", "dx");
        assertThat(count("SELECT count(*) FROM medical_records WHERE visit_id = " + visit + " AND locked_at IS NOT NULL"))
                .isEqualTo(1);
    }

    /** Gán lại trong lúc chờ khóa lượt: READ COMMITTED đọc lại người phụ trách sau khóa → 403, không ghi. */
    @Test
    void completionWaitsForTheVisitLockAndThenSeesTheNewAssignee() throws Exception {
        long visit = called(walkIn(medical), vetA);
        data(put(vetAToken, visit, Map.of("diagnosis", "dx")), HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from visits where id = % for no key update%",
                j -> j.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetB, visit),
                List.of(() -> complete(vetAToken, visit)), 1);

        assertScopeDenied(results.get(0));
        assertThat(xmins(visit).get("order")).isEqualTo(before.get("order"));
    }

    // ================================================================ helpers

    private List<ResponseEntity<Map<String, Object>>> whileHeld(String lockSql, Object[] lockArgs, String waitingLike,
            Consumer<JdbcTemplate> beforeRelease, List<Callable<ResponseEntity<Map<String, Object>>>> requests,
            int expectedWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList(lockSql, lockArgs);
                held.countDown();
                await(release);
                beforeRelease.accept(jdbc);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
            }
            awaitWaiting(waitingLike, expectedWaiting);
            assertThat(calls).as("request phải đứng chờ khóa").noneMatch(Future::isDone);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(String like, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, like);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy " + count + " request chờ khóa (" + like + ")");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Trigger tạm từ chối INSERT / UPDATE (23514 = check_violation → 409), giới hạn bằng {@code condition}. */
    private <T> T withRejectingTrigger(String event, String table, String condition, Supplier<T> action) {
        String name = "it_reject_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'IT rejects write' USING ERRCODE = '23514'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table + " FOR EACH ROW WHEN ("
                + condition + ") EXECUTE FUNCTION " + name + "()");
        try {
            return action.get();
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON " + table);
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    private long vaccineService() {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price)
                VALUES (?, 'MEDICAL', 'VACCINE', 120000) RETURNING id""", Long.class, "Tiêm " + UUID.randomUUID());
        vaccineServices.add(id);
        return id;
    }

    private long walkIn(long serviceId) {
        clock.set(T0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("petId", pet);
        request.put("serviceId", serviceId);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", request),
                HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    private long appointmentVisit(long serviceId, long assigneeId) {
        long appointmentPet = data.pet(customer);
        long appointment = data.appointment(customer, appointmentPet, branch, serviceId, "MEDICAL", TODAY,
                LocalTime.of(9, 30), "BOOKED", receptionist);
        clock.set(at(9, 10));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", appointmentPet);
        body.put("appointmentId", appointment);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", body),
                HttpStatus.CREATED);
        return called(((Number) visit.get("visitId")).longValue(), assigneeId);
    }

    private long assigned(long visitId, long assigneeId) {
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, receptionistToken,
                "/api/visits/" + visitId + "/assign", Map.of("assigneeId", assigneeId));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long called(long visitId, long assigneeId) {
        assigned(visitId, assigneeId);
        String bearer = assigneeId == vetA ? vetAToken : assigneeId == vetB ? vetBToken : caretakerToken;
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, bearer,
                "/api/visits/" + visitId + "/call", null);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    /** xmin của mọi bảng mà hoàn tất có thể ghi: lượt, lịch hẹn (nếu có), Order, bệnh án (nếu có). */
    private Map<String, Long> xmins(long visitId) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("visit", xmin("SELECT xmin::text::bigint FROM visits WHERE id = ?", visitId));
        result.put("order", xmin("SELECT xmin::text::bigint FROM orders WHERE visit_id = ?", visitId));
        Long appointment = jdbc.queryForObject("SELECT appointment_id FROM visits WHERE id = ?", Long.class, visitId);
        if (appointment != null) {
            result.put("appointment", xmin("SELECT xmin::text::bigint FROM appointments WHERE id = ?", appointment));
        }
        List<Long> record = jdbc.queryForList("SELECT xmin::text::bigint FROM medical_records WHERE visit_id = ?",
                Long.class, visitId);
        if (!record.isEmpty()) {
            result.put("record", record.get(0));
        }
        return result;
    }

    private long xmin(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private Map<String, Object> row(String sql) {
        return jdbc.queryForMap(sql);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long activeBranch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
    }

    private ResponseEntity<Map<String, Object>> complete(String bearer, long visitId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/%d/complete".formatted(visitId), null);
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, long visitId) {
        return exchange(HttpMethod.GET, bearer, "/api/visits/%d/medical-record".formatted(visitId), null);
    }

    private ResponseEntity<Map<String, Object>> getVisit(String bearer, long visitId) {
        return exchange(HttpMethod.GET, bearer, "/api/visits/%d".formatted(visitId), null);
    }

    private ResponseEntity<Map<String, Object>> put(String bearer, long visitId, Object body) {
        return exchange(HttpMethod.PUT, bearer, "/api/visits/%d/medical-record".formatted(visitId), body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers(bearer)), MAP);
    }

    private static HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static void assertScopeDenied(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitCompleteIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
