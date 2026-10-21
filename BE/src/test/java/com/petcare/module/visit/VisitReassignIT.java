package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * visit-v1 #9 gán lại lượt đã gọi (Visit#4) và #4 cho SUPER_MANAGER trên Postgres thật (docs/adr/0063). Mục theo plan:
 * <ul>
 * <li>Đường đúng: người phụ trách mới, gỡ cờ, lịch sử gán, quyền dòng Order, audit; POST = GET.</li>
 * <li>D1 (đủ điều kiện, A15), D2 (SUPER_MANAGER), D3 (#4 cho SUPER_MANAGER), D4 (409 trước 400 BR-TN-08); BR-TN-05;
 * ma trận body; vai trò / chéo chi nhánh.</li>
 * <li>G5 rollback bằng trigger tạm; race G1–G4 giữ khóa ở transaction khác, chờ trên {@code pg_stat_activity}.</li>
 * </ul>
 * Khóa A05–A08 hỏi {@code CashierShiftQueryApi} (placeholder ném lỗi, D016) nên bean đó được thay bằng mock trả "không
 * có ca mở" (khuôn {@code VisitProgressIT}). Đồng hồ {@link MutableClock} ở <b>năm 2009</b> (chưa IT nào dùng); chi
 * nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitReassignIT.TestBeans.class})
class VisitReassignIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2009, 4, 6);
    private static final Instant T0 = at(9, 0);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String STILL_ELIGIBLE = "Người phụ trách vẫn đang hoạt động; chỉ gán lại lượt đã gọi khi "
            + "người phụ trách không còn hoạt động (BR-TN-08)";
    private static final String STAFF_INACTIVE = "Nhân viên không hoạt động tại chi nhánh này (BR-TN-05)";
    private static final String WRONG_ROLE =
            "Lượt Khám/Tiêm phải gán bác sĩ thú y; lượt Thẩm mỹ phải gán nhân viên chăm sóc (BR-TN-05)";
    private static final String SCOPE_MESSAGE = "Không có quyền truy cập dữ liệu này";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** D016: placeholder của sales ném lỗi; mock mặc định trả {@code Optional.empty()} = không có ca mở. */
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
    private final List<Long> products = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long receptionist;
    private long vetA;
    private long vetB;
    private long vetC;
    private long caretaker;
    private long manager;
    private long superManager;
    private long admin;
    private long customer;
    private String receptionistToken;
    private String vetAToken;
    private String managerToken;
    private String superToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        for (long b : List.of(branch, otherBranch)) {
            data.branchService(b, medical, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        vetC = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        superManager = data.staff("SUPER_MANAGER", null);
        admin = data.staff("ADMIN", null);
        customer = data.counterCustomer("Khách gán lại lượt " + UUID.randomUUID(), null);
        receptionistToken = token(receptionist);
        vetAToken = token(vetA);
        managerToken = token(manager);
        superToken = token(superManager);
        adminToken = token(admin);
    }

    @AfterEach
    void tearDown() {
        for (long b : branches) {
            jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        for (long p : products) {
            jdbc.update("DELETE FROM products WHERE id = ?", p);
        }
        for (long c : categories) {
            jdbc.update("DELETE FROM product_categories WHERE id = ?", c);
        }
        data.cleanUp();
    }

    // ================================================================ đường đúng

    @Test
    void managerReassignsTheCalledVisitOfALockedVetWithHistoryOwnershipAndAudit() {
        long visit = called(walkIn(at(9, 0)), vetA);
        long vetLine = lineId(data(addLine(vetAToken, visit, Map.of("lineType", "SERVICE", "serviceId", medical,
                "quantity", 1)), HttpStatus.CREATED));
        long goodsLine = lineId(data(addLine(receptionistToken, visit, Map.of("lineType", "GOODS", "productId",
                product(), "quantity", 1)), HttpStatus.CREATED));
        long autoLine = jdbc.queryForObject("""
                SELECT l.id FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? AND l.is_auto_generated""", Long.class, visit);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visitRow(visit)).containsEntry("needs_reassign", true);
        Instant calledAt = ((Timestamp) visitRow(visit).get("called_at")).toInstant();
        long outbox = count("SELECT count(*) FROM notification_outbox");
        Instant now = at(10, 0).plusNanos(987_654_321);
        clock.set(now);
        String reason = "IT gán lại " + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = reassign(managerToken, visit, vetB, "  " + reason + "　");

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã gán lại lượt").containsEntry("code", 200);
        Map<String, Object> json = data(response);
        assertThat(json).containsEntry("status", "IN_PROGRESS").containsEntry("assigneeId", (int) vetB)
                .containsEntry("needsReassign", false);
        assertThat(Instant.parse((String) json.get("calledAt"))).as("giữ thời điểm gọi").isEqualTo(calledAt);
        assertThat(json).as("POST = GET #4").isEqualTo(data(get(managerToken, "/api/visits/" + visit)));

        Map<String, Object> row = visitRow(visit);
        assertThat(row).containsEntry("assignee_id", vetB).containsEntry("needs_reassign", false)
                .containsEntry("status", "IN_PROGRESS");
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));

        Map<String, Object> history = jdbc.queryForMap(
                "SELECT * FROM visit_assignments WHERE visit_id = ? ORDER BY id DESC LIMIT 1", visit);
        assertThat(history).containsEntry("from_account_id", vetA).containsEntry("to_account_id", vetB)
                .containsEntry("assigned_by", manager).containsEntry("visit_status_at_assign", "IN_PROGRESS")
                .containsEntry("reason", reason).containsEntry("assignee_was_offline", true);

        assertThat(owner(autoLine)).as("dòng tự sinh").isEqualTo(vetB);
        assertThat(owner(vetLine)).as("dòng người cũ đã thêm").isEqualTo(vetB);
        assertThat(owner(goodsLine)).as("dòng của lễ tân giữ nguyên").isEqualTo(receptionist);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_account_id, entity_type, entity_id, before_data::text AS b, after_data::text AS a
                FROM audit_logs WHERE action = 'VISIT_REASSIGNED' AND reason = ?""", reason);
        assertThat(audit).containsEntry("actor_account_id", manager).containsEntry("entity_type", "visits")
                .containsEntry("entity_id", visit);
        assertThat((String) audit.get("b")).contains("\"assigneeId\": " + vetA).contains("\"needsReassign\": true");
        assertThat((String) audit.get("a")).contains("\"assigneeId\": " + vetB).contains("\"needsReassign\": false");
        assertThat(count("SELECT count(*) FROM notification_outbox")).as("không thông báo").isEqualTo(outbox);

        assertThat(ids(list(get(receptionistToken, "/api/visits?needsReassign=true")))).doesNotContain(visit);
        assertThat(ids(list(get(token(vetB), "/api/me/queue")))).contains(visit);
    }

    /** D1 / A15: người phụ trách đổi chức vụ (docs/adr/0031 Q4) vẫn ACTIVE nhưng không còn phụ trách được lượt Khám. */
    @Test
    void assigneeWhoseRoleChangedCanBeReplacedWhileStillActive() {
        long visit = called(walkIn(at(9, 0)), vetA);
        jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", vetA);

        ResponseEntity<Map<String, Object>> response = reassign(managerToken, visit, vetB, "Đổi chức vụ");

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(visitRow(visit)).containsEntry("assignee_id", vetB).containsEntry("needs_reassign", false);
    }

    // ================================================================ BR-TN-08, BR-TN-05

    /** D042: mở khóa không gỡ cờ, nhưng #9 xét điều kiện thật của người phụ trách, không xét cờ. */
    @Test
    void activeAssigneeIsBrTn08EvenWithAStaleFlagAfterUnlock() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.POST, adminToken, "/api/accounts/" + vetA + "/unlock",
                Map.of("reason", "IT mở khóa")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = allXmins(visit);
        long logs = logs(visit);

        assertRule(reassign(managerToken, visit, vetB, "Thử gán lại"), STILL_ELIGIBLE);

        assertThat(allXmins(visit)).isEqualTo(before);
        assertThat(logs(visit)).isEqualTo(logs);
        assertThat(visitRow(visit)).containsEntry("needs_reassign", true).containsEntry("assignee_id", vetA);
    }

    @Test
    void newAssigneeMustBeAnActiveVetOfTheBranch() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        long lockedVet = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", lockedVet);
        long foreignVet = data.staff("VET", otherBranch);
        Map<String, Long> before = allXmins(visit);

        assertRule(reassign(managerToken, visit, lockedVet, "x"), STAFF_INACTIVE);
        assertRule(reassign(managerToken, visit, foreignVet, "x"), STAFF_INACTIVE);
        assertRule(reassign(managerToken, visit, 999_999_999L, "x"), STAFF_INACTIVE);
        assertRule(reassign(managerToken, visit, vetA, "x"), STAFF_INACTIVE);
        assertRule(reassign(managerToken, visit, caretaker, "x"), WRONG_ROLE);
        assertRule(reassign(managerToken, visit, receptionist, "x"), WRONG_ROLE);

        assertThat(allXmins(visit)).isEqualTo(before);
    }

    // ================================================================ D2, D3 — SUPER_MANAGER

    @Test
    void superManagerReassignsOnlyWhenTheBranchHasNoActiveManagerAndSeesTheDetail() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(get(superToken, "/api/visits/" + visit), HttpStatus.OK))
                .containsEntry("needsReassign", true);
        long logs = logs(visit);

        assertScopeDenied(reassign(superToken, visit, vetB, "Chi nhánh còn quản lý"));
        assertThat(logs(visit)).isEqualTo(logs);

        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", manager);
        ResponseEntity<Map<String, Object>> response = reassign(superToken, visit, vetB, "Quản lý cũng bị khóa");

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT assigned_by FROM visit_assignments WHERE visit_id = ? ORDER BY id DESC LIMIT 1", Long.class,
                visit)).isEqualTo(superManager);
        assertThat(get(superToken, "/api/visits?date=" + TODAY).getStatusCode()).as("#2 vẫn không cho SUPER_MANAGER")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rolesScopeAndNotFound() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = allXmins(visit);

        for (String bearer : List.of(receptionistToken, token(vetB), token(caretaker), adminToken)) {
            assertError(reassign(bearer, visit, vetB, "x"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertScopeDenied(reassign(token(data.staff("BRANCH_MANAGER", otherBranch)), visit, vetB, "x"));
        assertError(reassign(managerToken, 999_999_999L, vetB, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(get(adminToken, "/api/visits/" + visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        assertThat(allXmins(visit)).isEqualTo(before);
    }

    // ================================================================ D4 — 409 trước 400 BR-TN-08

    @Test
    void visitThatIsNotInProgressIs409EvenWhenTheAssigneeIsStillActive() {
        long waiting = assigned(walkIn(at(9, 0)), vetA);
        long completed = called(walkIn(at(9, 1)), vetA);
        jdbc.update("UPDATE visits SET status = 'COMPLETED', completed_at = now() WHERE id = ?", completed);
        long cancelled = walkIn(at(9, 2));
        assertThat(exchange(HttpMethod.POST, receptionistToken, "/api/visits/" + cancelled + "/cancel", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        for (long visit : List.of(waiting, completed, cancelled)) {
            Map<String, Long> before = allXmins(visit);
            assertError(reassign(managerToken, visit, vetB, "x"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
            assertThat(allXmins(visit)).isEqualTo(before);
        }
    }

    // ================================================================ body

    @Test
    void bodyMatrixIs400AndWritesNothing() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = allXmins(visit);
        String path = "/api/visits/" + visit + "/reassign";

        assertError(exchange(HttpMethod.POST, managerToken, path, Map.of("assigneeId", vetB)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        for (String blank : List.of("", "   ", "　")) {
            assertError(reassign(managerToken, visit, vetB, blank), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertError(reassign(managerToken, visit, vetB, "x".repeat(301)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(exchange(HttpMethod.POST, managerToken, path, Map.of("reason", "x")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(raw(managerToken, path, "{\"assigneeId\": 1.5, \"reason\": \"x\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(managerToken, path, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchange(HttpMethod.POST, receptionistToken, path, Map.of("assigneeId", vetB)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(allXmins(visit)).isEqualTo(before);
    }

    // ================================================================ G5 — một transaction

    @Test
    void failureOfAnyWriteRollsBackTheWholeReassignment() {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = allXmins(visit);
        long logs = logs(visit);

        assertError(withRejectingTrigger("INSERT", "visit_assignments", "NEW.visit_status_at_assign = 'IN_PROGRESS'",
                () -> reassign(managerToken, visit, vetB, "x")), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertError(withRejectingTrigger("UPDATE", "order_lines", "NEW.owner_account_id IS DISTINCT FROM "
                + "OLD.owner_account_id", () -> reassign(managerToken, visit, vetB, "x")), HttpStatus.CONFLICT,
                "CONCURRENCY_CONFLICT");
        String reason = "IT audit lỗi " + UUID.randomUUID();
        assertError(withRejectingTrigger("INSERT", "audit_logs", "NEW.action = 'VISIT_REASSIGNED'",
                () -> reassign(managerToken, visit, vetB, reason)), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");

        assertThat(allXmins(visit)).isEqualTo(before);
        assertThat(logs(visit)).isEqualTo(logs);
        assertThat(visitRow(visit)).containsEntry("assignee_id", vetA).containsEntry("needs_reassign", true);
    }

    // ================================================================ race G1–G4

    /** G1: khóa người mới đi trước — #9 chờ {@code FOR SHARE} rồi thấy người mới đã bị khóa → 400 BR-TN-05. */
    @Test
    void reassignWaitsForAConcurrentLockOfTheNewAssignee() throws Exception {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", new Object[] {vetB},
                "%from accounts where id = % for share%",
                j -> j.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", vetB),
                List.of(() -> reassign(managerToken, visit, vetB, "x")), 1);

        assertRule(results.get(0), STAFF_INACTIVE);
        assertThat(visitRow(visit)).containsEntry("assignee_id", vetA);
    }

    /**
     * G2: #9 đi trước — đang giữ {@code accounts FOR SHARE} của người mới (chờ dòng lượt) thì lần khóa người mới phải
     * chờ, rồi listener thấy lượt vừa chuyển cho người đó và đánh dấu lại cần gán lại.
     */
    @Test
    void lockOfTheNewAssigneeWaitsForTheReassignmentAndFlagsTheVisitAgain() throws Exception {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visit);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> reassigning =
                    pool.submit(() -> reassign(managerToken, visit, vetB, "x"));
            awaitWaiting("%from visits where id = % for no key update%", 1);
            Future<ResponseEntity<Map<String, Object>>> locking = pool.submit(() -> lock(vetB));
            awaitWaiting("%from accounts%for no key update%", 1);
            assertThat(locking.isDone()).as("khóa người mới phải chờ FOR SHARE của #9").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map<String, Object>> reassigned = reassigning.get(30, TimeUnit.SECONDS);
            assertThat(reassigned.getStatusCode()).as(String.valueOf(reassigned.getBody())).isEqualTo(HttpStatus.OK);
            assertThat(locking.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(visitRow(visit)).containsEntry("assignee_id", vetB).containsEntry("needs_reassign", true);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** G3: mở khóa người cũ đi trước — #9 chờ {@code FOR SHARE} rồi thấy người cũ đã hoạt động lại → 400 BR-TN-08. */
    @Test
    void reassignWaitsForAConcurrentUnlockOfTheOldAssignee() throws Exception {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", new Object[] {vetA},
                "%from accounts where id = % for share%",
                j -> j.update("UPDATE accounts SET is_locked = false, locked_reason = NULL WHERE id = ?", vetA),
                List.of(() -> reassign(managerToken, visit, vetB, "x")), 1);

        assertRule(results.get(0), STILL_ELIGIBLE);
        assertThat(visitRow(visit)).containsEntry("assignee_id", vetA);
    }

    /** G4: hai lần gán lại cùng lúc — lần sau thấy người phụ trách đã khác projection → 409, chỉ một lần ghi. */
    @Test
    void concurrentReassignmentsWriteOnceAndTheSecondIs409() throws Exception {
        long visit = called(walkIn(at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        long logs = logs(visit);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from visits where id = % for no key update%", j -> {
                },
                List.of(() -> reassign(managerToken, visit, vetB, "x"), () -> reassign(managerToken, visit, vetC, "y")),
                2);

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        ResponseEntity<Map<String, Object>> loser = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertError(loser, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(logs(visit)).isEqualTo(logs + 1);
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

    private long walkIn(Instant at) {
        clock.set(at);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", data.pet(customer));
        body.put("serviceId", medical);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", body),
                HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    private long assigned(long visitId, long assigneeId) {
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, receptionistToken,
                "/api/visits/" + visitId + "/assign", Map.of("assigneeId", assigneeId));
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long called(long visitId, long assigneeId) {
        assigned(visitId, assigneeId);
        String bearer = assigneeId == vetA ? vetAToken : token(assigneeId);
        ResponseEntity<Map<String, Object>> response = exchange(HttpMethod.POST, bearer,
                "/api/visits/" + visitId + "/call", null);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long product() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "IT reassign " + UUID.randomUUID());
        categories.add(category);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, unit, price)
                VALUES (?, ?, 'Pate', 'GOODS', 'hộp', 30000) RETURNING id""", Long.class, category,
                "IT-" + UUID.randomUUID().toString().substring(0, 12));
        products.add(id);
        return id;
    }

    private ResponseEntity<Map<String, Object>> addLine(String bearer, long visitId, Map<String, Object> body) {
        return exchange(HttpMethod.POST, bearer, "/api/orders/" + orderOf(visitId) + "/lines", body);
    }

    private static long lineId(Map<String, Object> result) {
        return ((Number) result.get("lineId")).longValue();
    }

    private long owner(long lineId) {
        return jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE id = ?", Long.class, lineId);
    }

    private long orderOf(long visitId) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visitId);
    }

    private Map<String, Object> visitRow(long visitId) {
        return jdbc.queryForMap("SELECT * FROM visits WHERE id = ?", visitId);
    }

    private long xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text::bigint FROM " + table + " WHERE id = ?", Long.class, id);
    }

    /** {@code visits} + {@code orders} + {@code order_lines} của lượt. */
    private Map<String, Long> allXmins(long visitId) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("visit", xmin("visits", visitId));
        result.put("order", jdbc.queryForObject("SELECT xmin::text::bigint FROM orders WHERE visit_id = ?", Long.class,
                visitId));
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT l.id, l.xmin::text::bigint AS x FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? ORDER BY l.id""", visitId)) {
            result.put("line#" + row.get("id"), ((Number) row.get("x")).longValue());
        }
        return result;
    }

    private long logs(long visitId) {
        return count("SELECT count(*) FROM visit_assignments WHERE visit_id = " + visitId);
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

    private ResponseEntity<Map<String, Object>> lock(long accountId) {
        return exchange(HttpMethod.POST, adminToken, "/api/accounts/" + accountId + "/lock",
                Map.of("reason", "IT khóa " + UUID.randomUUID()));
    }

    private ResponseEntity<Map<String, Object>> reassign(String bearer, long visitId, long assigneeId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assigneeId", assigneeId);
        body.put("reason", reason);
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/reassign", body);
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, String path) {
        return exchange(HttpMethod.GET, bearer, path, null);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        return http.exchange(URI.create(http.getRootUri() + path), method,
                new HttpEntity<>(body, headers(bearer)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String bearer, String path, String body) {
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST,
                new HttpEntity<>(body, headers(bearer)), MAP);
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
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return data(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static List<Long> ids(List<Map<String, Object>> visits) {
        return visits.stream().map(v -> ((Number) v.get("visitId")).longValue()).toList();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String message) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION")
                .containsEntry("message", message);
    }

    private static void assertScopeDenied(ResponseEntity<Map<String, Object>> response) {
        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(response.getBody()).containsEntry("message", SCOPE_MESSAGE);
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitReassignIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
