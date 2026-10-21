package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.service.OrderQueryService;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #5 gán, #6 sắp lại, #7 xác định ca cấp cứu trên Postgres thật (docs/adr/0061). Mục theo plan:
 * <ul>
 * <li>Đường đúng + F4 T1–T3 (phải có / không được có), JSON = GET (E), Q2 chuyển dòng.</li>
 * <li>Rule BR-TN-05 / BR-TN-04 / 409 (D4, GU), thứ tự lỗi (D6), ma trận đầu vào (D3).</li>
 * <li>Rollback T4–T8 bằng trigger tạm và spy, vai trò / phiên / chéo chi nhánh (A5).</li>
 * <li>Race (L3 I1–I4, T11): giữ khóa ở transaction khác, chờ trên {@code pg_stat_activity}.</li>
 * </ul>
 * Lượt được tạo bằng {@code POST /api/visits} thật (có Order + dòng tự sinh). Đồng hồ {@link MutableClock} ở <b>năm
 * 2006</b> (chưa IT nào dùng); chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitDispatchIT.TestBeans.class})
class VisitDispatchIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2006, 3, 20);
    private static final Instant T0 = at(9, 0);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String[] VISIT_KEYS = {"visitId", "code", "branchId", "customerId", "customerName", "petId",
        "petName", "species", "appointmentId", "serviceGroup", "status", "priorityClass", "queueSortKey", "assigneeId",
        "assigneeName", "needsReassign", "orderId", "checkedInAt", "calledAt", "completedAt", "cancelledAt",
        "cancelReason"};
    private static final String STAFF_INACTIVE = "Nhân viên không hoạt động tại chi nhánh này (BR-TN-05)";
    private static final String WRONG_ROLE =
            "Lượt Khám/Tiêm phải gán bác sĩ thú y; lượt Thẩm mỹ phải gán nhân viên chăm sóc (BR-TN-05)";
    private static final String EMERGENCY_BOUNDARY =
            "Ca cấp cứu chỉ sắp trong nhóm cấp cứu; muốn đưa lượt lên nhóm cấp cứu hãy xác định ca cấp cứu (BR-TN-04)";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** T6: bước dựng response (sau mọi lần ghi) lỗi → rollback; bean không có proxy {@code @Transactional}. */
    @MockitoSpyBean
    private OrderQueryService orderQueries;

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
    @Autowired
    private VisitRepository visitRepository;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> products = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long medical2;
    private long grooming;
    private long receptionist;
    private long vetA;
    private long vetB;
    private long caretaker;
    private long customer;
    private String receptionistToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        medical2 = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch)) {
            data.branchService(b, medical, true);
            data.branchService(b, medical2, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        customer = data.counterCustomer("Khách điều phối " + UUID.randomUUID(), null);
        receptionistToken = token(receptionist);
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

    // ================================================================ #5 gán — đường đúng (T1–T3, E)

    @Test
    void firstAssignmentPersistsVisitLogAndAutoLineOwnerTogether() {
        long visit = walkIn(medical, at(9, 0));
        long order = orderOf(visit);
        long auditsBefore = count("SELECT count(*) FROM audit_logs");
        long outboxBefore = count("SELECT count(*) FROM notification_outbox");
        clock.set(at(9, 5));

        ResponseEntity<Map<String, Object>> response = assign(receptionistToken, visit, vetA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã gán nhân viên").containsEntry("code", 200);
        Map<String, Object> result = data(response);
        assertThat(result).containsOnlyKeys("visit", "assigneeOffline").containsEntry("assigneeOffline", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) result.get("visit");
        assertThat(json).containsOnlyKeys(VISIT_KEYS).containsEntry("assigneeId", (int) vetA)
                .containsEntry("status", "WAITING").containsEntry("orderId", (int) order)
                .containsEntry("assigneeName", staffName(vetA));
        assertThat(json).as("POST = GET #4").isEqualTo(data(get(receptionistToken, "/api/visits/" + visit)));

        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class, visit))
                .isEqualTo(vetA);
        assertThat(jdbc.queryForMap("SELECT * FROM visit_assignments WHERE visit_id = ?", visit))
                .containsEntry("from_account_id", null).containsEntry("to_account_id", vetA)
                .containsEntry("assigned_by", receptionist).containsEntry("visit_status_at_assign", "WAITING")
                .containsEntry("reason", null).containsEntry("assignee_was_offline", true)
                .satisfies(row -> assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(at(9, 5)));
        assertThat(jdbc.queryForMap("SELECT owner_account_id, added_by FROM order_lines WHERE order_id = ?", order))
                .containsEntry("owner_account_id", vetA).containsEntry("added_by", receptionist);
        assertThat(count("SELECT count(*) FROM audit_logs")).as("không audit (08 §8.3)").isEqualTo(auditsBefore);
        assertThat(count("SELECT count(*) FROM notification_outbox")).as("không thông báo").isEqualTo(outboxBefore);
        assertInvariants();
    }

    @Test
    void reassigningMovesTheAutoLineAndTheOldAssigneesLinesButNotTheReceptionistsGoods() {
        long visit = walkIn(medical, at(9, 0));
        long order = orderOf(visit);
        assertThat(assign(receptionistToken, visit, vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenA = token(vetA);
        assertThat(exchange(HttpMethod.POST, tokenA, "/api/orders/" + order + "/lines",
                Map.of("lineType", "SERVICE", "serviceId", medical2, "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        long goods = product();
        assertThat(exchange(HttpMethod.POST, receptionistToken, "/api/orders/" + order + "/lines",
                Map.of("lineType", "GOODS", "productId", goods, "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Long> addedBy = column(order, "added_by");

        ResponseEntity<Map<String, Object>> response = assign(receptionistToken, visit, vetB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> owners = column(order, "owner_account_id");
        assertThat(owners).containsEntry("auto", vetB).containsEntry("service", vetB).containsEntry("goods",
                receptionist);
        assertThat(column(order, "added_by")).as("chỉ đổi chủ, không đổi người thêm").isEqualTo(addedBy);
        assertThat(jdbc.queryForList("SELECT from_account_id FROM visit_assignments WHERE visit_id = ? ORDER BY id",
                Long.class, visit)).containsExactly(null, vetA);

        long autoLine = lineId(order, "auto");
        assertThat(exchange(HttpMethod.DELETE, tokenA, "/api/orders/" + order + "/lines/" + autoLine, null)
                .getStatusCode()).as("người cũ hết quyền (BR-BH-02)").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.DELETE, token(vetB), "/api/orders/" + order + "/lines/" + autoLine, null)
                .getStatusCode()).as("người được gán đổi / xóa dòng tự sinh (BR-TN-01)").isEqualTo(HttpStatus.OK);
        assertInvariants();
    }

    @Test
    void assigningTheCurrentAssigneeAgainWritesNothing() {
        long visit = walkIn(medical, at(9, 0));
        assign(receptionistToken, visit, vetA);
        Map<String, Long> before = xmins(visit);
        long logs = logs(visit);

        ResponseEntity<Map<String, Object>> again = assign(receptionistToken, visit, vetA);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(again)).containsEntry("assigneeOffline", true);
        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(logs(visit)).isEqualTo(logs);
    }

    @Test
    void offlineIsOnlyAWarningAndItsBoundaryCountsAsOnline() {
        long visit = walkIn(medical, at(9, 0));
        clock.set(at(10, 0));
        jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(at(9, 50)), vetA);
        jdbc.update("UPDATE accounts SET last_seen_at = ? WHERE id = ?", Timestamp.from(at(9, 49)), vetB);

        assertThat(data(assign(receptionistToken, visit, vetA))).as("đúng mốc 10 phút [CFG]")
                .containsEntry("assigneeOffline", false);
        assertThat(data(assign(receptionistToken, visit, vetB))).as("quá mốc: cảnh báo, vẫn gán")
                .containsEntry("assigneeOffline", true);
        assertThat(jdbc.queryForList("SELECT assignee_was_offline FROM visit_assignments WHERE visit_id = ? "
                + "ORDER BY id", Boolean.class, visit)).containsExactly(false, true);
    }

    // ================================================================ #5 gán — rule (D4, GU1)

    @Test
    void assigneeMustBeAnActiveStaffMemberOfTheVisitsBranchWithTheRightRole() {
        long medicalVisit = walkIn(medical, at(9, 0));
        long groomingVisit = walkIn(grooming, at(9, 5));
        long disabled = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        long locked = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        long foreign = data.staff("VET", otherBranch);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customerAccount = data.customerAccount();
        long otherReceptionist = data.staff("RECEPTIONIST", branch);
        Map<String, Long> before = xmins(medicalVisit);

        for (long assignee : List.of(disabled, locked, foreign, superManager, customerAccount, Long.MAX_VALUE)) {
            assertRule(assign(receptionistToken, medicalVisit, assignee), STAFF_INACTIVE);
        }
        assertRule(assign(receptionistToken, medicalVisit, caretaker), WRONG_ROLE);
        assertRule(assign(receptionistToken, medicalVisit, otherReceptionist), WRONG_ROLE);
        assertRule(assign(receptionistToken, groomingVisit, vetA), WRONG_ROLE);
        assertThat(xmins(medicalVisit)).isEqualTo(before);
        assertThat(logs(medicalVisit) + logs(groomingVisit)).isZero();

        long temporarilyLocked = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(at(11, 0)), temporarilyLocked);
        assertThat(assign(receptionistToken, medicalVisit, temporarilyLocked).getStatusCode())
                .as("khóa tạm do đăng nhập sai không tính (ADR-0024)").isEqualTo(HttpStatus.OK);
        assertThat(assign(receptionistToken, groomingVisit, caretaker).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void calledOrFinishedVisitsCannotBeAssigned() {
        long visit = walkIn(medical, at(9, 0));
        for (String status : List.of("IN_PROGRESS", "COMPLETED", "CANCELLED")) {
            jdbc.update("UPDATE visits SET status = ?, assignee_id = ? WHERE id = ?", status, vetA, visit);
            ResponseEntity<Map<String, Object>> response = assign(receptionistToken, visit, vetB);
            assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
            assertThat(response.getBody().get("message")).asString()
                    .isEqualTo("Không thể chuyển trạng thái lượt tiếp nhận từ " + status + " sang WAITING");
        }
        assertThat(logs(visit)).isZero();
    }

    // ================================================================ #6 sắp lại

    @Test
    void moveReturnsTheBranchQueueAndPersistsTheNewKey() {
        long a = walkIn(medical, at(9, 0));
        long b = walkIn(medical, at(9, 10));
        long c = walkIn(grooming, at(9, 20));
        long foreign = walkInAt(otherBranch, medical, at(9, 30));

        ResponseEntity<Map<String, Object>> response = move(receptionistToken, c, b);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã sắp lại hàng đợi");
        List<Map<String, Object>> queue = list(response);
        assertThat(ids(queue)).containsExactly(a, c, b).doesNotContain(foreign);
        assertThat(queue).as("= GET #2 không lọc").isEqualTo(list(get(receptionistToken, "/api/visits")));
        assertThat(key(c)).isEqualTo(at(9, 10).minusNanos(1_000));
        assertThat(Instant.parse((String) queue.get(1).get("queueSortKey"))).isEqualTo(key(c));
        assertThat(key(a)).isEqualTo(at(9, 0));
        assertThat(key(b)).isEqualTo(at(9, 10));
    }

    @Test
    void appointmentsSharingTheSameSlotAreSeparatedByShiftingTheTail() {
        long a = appointmentVisit(LocalTime.of(9, 30), at(9, 0));
        long b = appointmentVisit(LocalTime.of(9, 30), at(9, 1));
        long c = appointmentVisit(LocalTime.of(9, 30), at(9, 2));
        assertThat(List.of(key(a), key(b), key(c))).as("cùng khóa = giờ hẹn").containsOnly(at(9, 30));

        assertThat(ids(list(move(receptionistToken, c, b)))).containsExactly(a, c, b);

        assertThat(key(a)).isEqualTo(at(9, 30));
        assertThat(key(c)).isAfter(key(a));
        assertThat(key(b)).isAfter(key(c));
        assertThat(priority(c)).isEqualTo("APPOINTMENT");
    }

    @Test
    void walkInPlacedBeforeAnAppointmentTakesTheAppointmentClassButKeepsNoAppointment() {
        long appointment = appointmentVisit(LocalTime.of(9, 30), at(9, 0));
        long walkIn = walkIn(medical, at(9, 5));

        List<Map<String, Object>> queue = list(move(receptionistToken, walkIn, appointment));

        assertThat(ids(queue)).containsExactly(walkIn, appointment);
        assertThat(queue.get(0)).containsEntry("priorityClass", "APPOINTMENT").containsEntry("appointmentId", null);
        assertThat(priority(walkIn)).isEqualTo("APPOINTMENT");
    }

    @Test
    void moveRejectsInvalidTargetsAndWritesNothing() {
        long a = walkIn(medical, at(9, 0));
        long b = walkIn(medical, at(9, 10));
        long emergency = walkIn(medical, at(9, 20));
        assertThat(emergency(receptionistToken, emergency).getStatusCode()).isEqualTo(HttpStatus.OK);
        long foreign = walkInAt(otherBranch, medical, at(9, 30));
        Map<String, Long> before = xmins(a);

        assertRule(move(receptionistToken, a, a), "Không đặt lượt trước chính nó được (BR-TN-04)");
        assertError(move(receptionistToken, a, Long.MAX_VALUE), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertRule(move(receptionistToken, a, foreign), "Lượt đích thuộc chi nhánh khác (BR-TN-04)");
        assertRule(move(receptionistToken, a, emergency), EMERGENCY_BOUNDARY);
        assertRule(move(receptionistToken, emergency, a), EMERGENCY_BOUNDARY);

        jdbc.update("UPDATE visits SET status = 'IN_PROGRESS', assignee_id = ? WHERE id = ?", vetA, b);
        assertError(move(receptionistToken, a, b), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(move(receptionistToken, b, a), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(xmins(a)).isEqualTo(before);
    }

    @Test
    void moveIntoTheCurrentPlaceWritesNothing() {
        long a = walkIn(medical, at(9, 0));
        long b = walkIn(medical, at(9, 10));
        Map<String, Long> beforeA = xmins(a);
        Map<String, Long> beforeB = xmins(b);

        assertThat(ids(list(move(receptionistToken, a, b)))).containsExactly(a, b);
        assertThat(xmins(a)).isEqualTo(beforeA);
        assertThat(xmins(b)).isEqualTo(beforeB);
    }

    // ================================================================ #7 cấp cứu

    @Test
    void markEmergencyByReceptionistOrVetPutsTheVisitFirstWithTheMarkingTime() {
        long early = walkIn(medical, at(9, 0));
        long late = walkIn(medical, at(9, 30));
        clock.set(at(9, 40).plusNanos(123_456_789));

        ResponseEntity<Map<String, Object>> response = emergency(token(vetA), late);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã xác định ca cấp cứu");
        Map<String, Object> json = data(response);
        Instant micros = at(9, 40).plusNanos(123_456_000);
        assertThat(json).containsEntry("priorityClass", "EMERGENCY");
        assertThat(Instant.parse((String) json.get("queueSortKey"))).isEqualTo(micros).isEqualTo(key(late));
        assertThat(json).as("POST = GET #4").isEqualTo(data(get(receptionistToken, "/api/visits/" + late)));
        assertThat(ids(list(get(receptionistToken, "/api/visits")))).containsExactly(late, early);

        clock.set(at(9, 50));
        assertThat(emergency(receptionistToken, early).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(list(get(receptionistToken, "/api/visits")))).as("cấp cứu xếp theo lúc được xác định")
                .containsExactly(late, early);
    }

    @Test
    void markingTwiceOrAfterTheCallIs409AndWritesNothing() {
        long visit = walkIn(medical, at(9, 0));
        assertThat(emergency(receptionistToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = xmins(visit);

        ResponseEntity<Map<String, Object>> twice = emergency(receptionistToken, visit);
        assertError(twice, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(twice.getBody().get("message")).asString().contains("hạng ưu tiên của lượt");
        assertThat(xmins(visit)).isEqualTo(before);

        long called = walkIn(medical, at(9, 5));
        jdbc.update("UPDATE visits SET status = 'IN_PROGRESS', assignee_id = ? WHERE id = ?", vetA, called);
        assertError(emergency(receptionistToken, called), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    // ================================================================ rollback (F4 T4–T8)

    @Test
    void failingToWriteTheAssignmentLogRollsBackTheAssignmentAndTheLineOwner() {
        long visit = walkIn(medical, at(9, 0));
        long order = orderOf(visit);

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("INSERT", "visit_assignments",
                "NEW.visit_id = " + visit, () -> assign(receptionistToken, visit, vetA));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class, visit)).isNull();
        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE order_id = ?", Long.class,
                order)).isEqualTo(receptionist);
        assertThat(logs(visit)).isZero();
    }

    @Test
    void salesRejectingTheOrderAfterTheVisitWasWrittenRollsBackEverything() {
        long visit = walkIn(medical, at(9, 0));
        jdbc.update("UPDATE orders SET status = 'PENDING', pending_at = now() WHERE visit_id = ?", visit);

        ResponseEntity<Map<String, Object>> response = assign(receptionistToken, visit, vetA);

        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class, visit)).isNull();
        assertThat(logs(visit)).as("visit và log đã flush trước lời gọi sales vẫn bị rollback").isZero();
    }

    @Test
    void failureWhileBuildingTheResponseRollsBackTheWrites() {
        long visit = walkIn(medical, at(9, 0));
        long order = orderOf(visit);
        doThrow(new IllegalStateException("IT: lỗi khi dựng response")).when(orderQueries)
                .findOrderIdsByVisitIds(any());

        assertError(assign(receptionistToken, visit, vetA), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class, visit)).isNull();
        assertThat(jdbc.queryForObject("SELECT owner_account_id FROM order_lines WHERE order_id = ?", Long.class,
                order)).isEqualTo(receptionist);
        assertThat(logs(visit)).isZero();
    }

    @Test
    void failingHalfwayThroughAShiftLeavesEveryKeyUntouched() {
        long a = appointmentVisit(LocalTime.of(9, 30), at(9, 0));
        long b = appointmentVisit(LocalTime.of(9, 30), at(9, 1));
        long c = appointmentVisit(LocalTime.of(9, 30), at(9, 2));
        long d = walkIn(medical, at(9, 3));
        // d (WALK_IN) đặt trước b: d nhận hạng APPOINTMENT, khóa = khóa a + 1µs, nên phải dồn b rồi c; chặn UPDATE của c.
        Map<Long, Instant> before = Map.of(a, key(a), b, key(b), c, key(c), d, key(d));

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "visits", "NEW.id = " + c,
                () -> move(receptionistToken, d, b));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        for (Map.Entry<Long, Instant> entry : before.entrySet()) {
            assertThat(key(entry.getKey())).as("lượt #" + entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(priority(d)).isEqualTo("WALK_IN");
    }

    @Test
    void failingToWriteTheEmergencyKeepsTheOldClass() {
        long visit = walkIn(medical, at(9, 0));

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "visits", "NEW.id = " + visit,
                () -> emergency(receptionistToken, visit));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(priority(visit)).isEqualTo("WALK_IN");
        assertThat(key(visit)).isEqualTo(at(9, 0));
    }

    @Test
    void lockQueriesRefuseToRunOutsideATransaction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> visitRepository.lockById(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> visitRepository.lockWaitingIdsOfBranch(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ================================================================ thứ tự lỗi (D6)

    @Test
    void precedenceIsRoleThenExistenceThenScopeThenRuleThenState() {
        long visit = walkIn(medical, at(9, 0));
        long foreign = walkInAt(otherBranch, medical, at(9, 5));

        assertError(assign(token(vetA), Long.MAX_VALUE, caretaker), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(assign(receptionistToken, Long.MAX_VALUE, caretaker), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(assign(receptionistToken, foreign, caretaker), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        jdbc.update("UPDATE visits SET status = 'IN_PROGRESS', assignee_id = ? WHERE id = ?", vetA, visit);
        assertRule(assign(receptionistToken, visit, caretaker), WRONG_ROLE);

        long target = walkIn(medical, at(9, 10));
        assertThat(emergency(receptionistToken, target).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertRule(move(receptionistToken, visit, target), EMERGENCY_BOUNDARY);
        assertError(move(receptionistToken, foreign, Long.MAX_VALUE), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");

        long fresh = data.staff("RECEPTIONIST", branch);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", fresh);
        assertError(rawAssign(token(fresh), "/api/visits/" + visit + "/assign", "{}"), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION");
    }

    // ================================================================ ma trận đầu vào (D3)

    @Test
    void inputMatrix() {
        long visit = walkIn(medical, at(9, 0));
        Map<String, Long> before = xmins(visit);
        String assignPath = "/api/visits/" + visit + "/assign";
        String movePath = "/api/visits/" + visit + "/move";

        assertError(rawAssign(receptionistToken, assignPath, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(rawAssign(receptionistToken, assignPath, "{"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(rawAssign(receptionistToken, assignPath, "[]"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(rawAssign(receptionistToken, assignPath, "{}").getBody())
                .containsEntry("errorCode", "VALIDATION_FAILED")
                .containsEntry("message", "assigneeId: Thiếu nhân viên được gán");
        assertThat(rawAssign(receptionistToken, assignPath, "{\"assigneeId\": 0}").getBody())
                .containsEntry("message", "assigneeId: Mã nhân viên không hợp lệ");
        assertThat(rawAssign(receptionistToken, assignPath, "{\"assigneeId\": -1}").getBody())
                .containsEntry("errorCode", "VALIDATION_FAILED");
        for (String bad : List.of("1.5", "\"abc\"", "{}", "true", "[" + vetA + "]", "9223372036854775808")) {
            assertError(rawAssign(receptionistToken, assignPath, "{\"assigneeId\": " + bad + "}"),
                    HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        assertThat(rawAssign(receptionistToken, movePath, "{}").getBody())
                .containsEntry("message", "placeBeforeVisitId: Thiếu lượt đích");
        assertError(rawAssign(receptionistToken, movePath, "{\"placeBeforeVisitId\": 1.5}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(rawAssign(receptionistToken, "/api/visits/abc/assign", "{\"assigneeId\": 1}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(exchange(HttpMethod.POST, receptionistToken, "/api/visits/0/mark-emergency", null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(http.exchange(URI.create(http.getRootUri() + assignPath), HttpMethod.POST,
                new HttpEntity<>("x", headers(receptionistToken, MediaType.TEXT_PLAIN)), MAP).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exchange(HttpMethod.PUT, receptionistToken, assignPath, Map.of("assigneeId", vetA))
                .getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(xmins(visit)).isEqualTo(before);

        // Ghim hành vi Jackson mặc định (không đổi cấu hình): số viết dạng chuỗi được đọc thành số, như isEmergency.
        assertThat(rawAssign(receptionistToken, assignPath, "{\"assigneeId\": \"" + vetA + "\", \"branchId\": 1}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // #7 không có body: body / kiểu nội dung lạ bị bỏ qua.
        assertThat(http.exchange(URI.create(http.getRootUri() + "/api/visits/" + visit + "/mark-emergency"),
                HttpMethod.POST, new HttpEntity<>("x", headers(receptionistToken, MediaType.TEXT_PLAIN)), MAP)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================================================================ vai trò, phiên, chéo chi nhánh (A5)

    @Test
    void rolesMatrix() {
        long visit = walkIn(medical, at(9, 0));
        long other = walkIn(medical, at(9, 5));
        Map<String, Long> before = xmins(visit);
        for (String role : List.of("VET", "CARETAKER", "BRANCH_MANAGER")) {
            String bearer = token(data.staff(role, branch));
            assertError(assign(bearer, visit, vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(move(bearer, visit, other), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("CARETAKER", "BRANCH_MANAGER")) {
            assertError(emergency(token(data.staff(role, branch)), visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String bearer : List.of(token(data.staff("SUPER_MANAGER", null)), token(data.staff("ADMIN", null)),
                token(data.customerAccount()))) {
            assertError(assign(bearer, visit, vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(move(bearer, visit, other), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(emergency(bearer, visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(xmins(visit)).isEqualTo(before);
        assertThat(rawAssign(token(vetA), "/api/visits/" + visit + "/assign", "{}").getBody())
                .as("body sai đứng trước vai trò").containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void sessionAndAccountStateAreCheckedOnEveryRequest() {
        long visit = walkIn(medical, at(9, 0));
        assertError(assign(null, visit, vetA), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(assign("not-a-jwt", visit, vetA), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        long revoked = data.staff("RECEPTIONIST", branch);
        String revokedToken = token(revoked);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", revoked);
        assertError(assign(revokedToken, visit, vetA), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        long locked = data.staff("RECEPTIONIST", branch);
        String lockedToken = token(locked);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        assertError(emergency(lockedToken, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        long disabled = data.staff("RECEPTIONIST", branch);
        String disabledToken = token(disabled);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        assertError(move(disabledToken, visit, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(logs(visit)).isZero();
    }

    @Test
    void roleAndBranchComeFromTheDatabaseOnEveryRequest() {
        long visit = walkIn(medical, at(9, 0));
        long moved = data.staff("RECEPTIONIST", branch);
        String movedToken = token(moved);

        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", moved);
        assertError(assign(movedToken, visit, vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(emergency(movedToken, visit).getStatusCode()).as("VET được xác định cấp cứu")
                .isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", moved);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, moved);
        assertError(assign(movedToken, visit, vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void nothingCrossesTheBranchBoundary() {
        long visit = walkIn(medical, at(9, 0));
        long foreign = walkInAt(otherBranch, medical, at(9, 5));

        assertError(assign(receptionistToken, foreign, vetA), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(move(receptionistToken, foreign, visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(emergency(receptionistToken, foreign), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertRule(assign(receptionistToken, visit, data.staff("VET", otherBranch)), STAFF_INACTIVE);
        assertRule(assign(receptionistToken, visit, Long.MAX_VALUE), STAFF_INACTIVE);
        assertThat(priority(foreign)).isEqualTo("WALK_IN");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM visits WHERE id = ?", Long.class, foreign)).isNull();
    }

    // ================================================================ race (L3)

    /** I1: điều chuyển / vô hiệu hóa / khóa người được gán giữ {@code accounts FOR NO KEY UPDATE}; gán chờ rồi thấy mới. */
    @Test
    void assignWaitsForAConcurrentTransferDisableOrLockOfTheAssignee() throws Exception {
        long visit = walkIn(medical, at(9, 0));
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("chuyển chi nhánh", "UPDATE staff_profiles SET branch_id = " + otherBranch + " WHERE account_id = ?");
        changes.put("vô hiệu hóa", "UPDATE accounts SET status = 'DISABLED' WHERE id = ?");
        changes.put("khóa", "UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?");
        for (Map.Entry<String, String> change : changes.entrySet()) {
            long vet = data.staff("VET", branch);
            List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                    "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", new Object[] {vet},
                    "%from accounts where id = % for share%", j -> j.update(change.getValue(), vet),
                    List.of(() -> assign(receptionistToken, visit, vet)), 1);
            assertRule(results.get(0), STAFF_INACTIVE);
        }
        assertThat(logs(visit)).isZero();
    }

    /** Chiều ngược lại: gán đang giữ khóa chia sẻ thì điều chuyển phải chờ và sau đó thấy lượt được gán. */
    @Test
    void transferWaitsForAnAssignmentHoldingTheShareLock() throws Exception {
        long visit = walkIn(medical, at(9, 0));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> assigning = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR SHARE", vetA);
                jdbc.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetA, visit);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> transfer = pool.submit(() -> tx.execute(s -> {
                jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", vetA);
                return jdbc.queryForObject("SELECT count(*) FROM visits WHERE assignee_id = ? "
                        + "AND status IN ('WAITING', 'IN_PROGRESS')", Integer.class, vetA);
            }));
            awaitWaiting("%from accounts where id = % for no key update%", 1);
            assertThat(transfer.isDone()).isFalse();
            release.countDown();
            assigning.get(30, TimeUnit.SECONDS);
            assertThat(transfer.get(30, TimeUnit.SECONDS)).as("BR-QT-08 thấy việc dở").isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Request của người được gán không bị treo vì khóa chia sẻ: {@code touchLastSeen} dùng SKIP LOCKED. */
    @Test
    void theAssigneesOwnRequestsAreNotBlockedByTheShareLock() throws Exception {
        String vetToken = token(vetA);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR SHARE", vetA);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> request = pool.submit(() -> get(vetToken, "/api/me/queue"));
            assertThat(request.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** T11: bấm đúp — hai request gán cùng người chờ khóa lượt; request sau thấy đã gán → no-op, đúng một log. */
    @Test
    void doubleClickedAssignmentWritesOneLog() throws Exception {
        long visit = walkIn(medical, at(9, 0));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {visit},
                "%from visits where id = % for no key update%", j -> { },
                List.of(() -> assign(receptionistToken, visit, vetA), () -> assign(receptionistToken, visit, vetA)),
                2);

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(logs(visit)).isEqualTo(1);
    }

    /** I4: lượt đích được gọi trong lúc sắp lại chờ khóa → 409; lượt được gán trong lúc chờ → gán lại thấy người mới. */
    @Test
    void visitsCalledWhileWaitingForTheLockAreRejected() throws Exception {
        long a = walkIn(medical, at(9, 0));
        long b = walkIn(medical, at(9, 10));

        List<ResponseEntity<Map<String, Object>>> move = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {a},
                "%for no key update%", j -> j.update("UPDATE visits SET status = 'IN_PROGRESS', assignee_id = ?, "
                        + "called_at = now() WHERE id = ?", vetA, a),
                List.of(() -> move(receptionistToken, b, a)), 1);
        assertError(move.get(0), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        List<ResponseEntity<Map<String, Object>>> assign = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {b},
                "%from visits where id = % for no key update%",
                j -> j.update("UPDATE visits SET assignee_id = ? WHERE id = ?", vetA, b),
                List.of(() -> assign(receptionistToken, b, vetB)), 1);
        assertThat(assign.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT from_account_id FROM visit_assignments WHERE visit_id = ?", Long.class,
                b)).as("đọc người phụ trách dưới khóa").isEqualTo(vetA);
    }

    /** I3: hai lần sắp cùng chi nhánh cùng chờ câu khóa tập và chạy lần lượt; kết quả là một trong hai thứ tự tuần tự. */
    @Test
    void concurrentMovesInOneBranchRunOneAfterTheOther() throws Exception {
        long a = walkIn(medical, at(9, 0));
        long b = walkIn(medical, at(9, 10));
        long c = walkIn(medical, at(9, 20));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", new Object[] {a},
                "%status = 'waiting'%for no key update%", j -> { },
                List.of(() -> move(receptionistToken, c, a), () -> move(receptionistToken, b, a)), 2);

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        List<Long> order = ids(list(get(receptionistToken, "/api/visits")));
        assertThat(order).isIn(List.of(c, b, a), List.of(b, c, a));
        List<Instant> keys = order.stream().map(this::key).toList();
        assertThat(keys).isSortedAccordingTo(Instant::compareTo).doesNotHaveDuplicates();
    }

    // ================================================================ helpers

    /** F4: lượt WAITING đã gán thì mọi dòng tự sinh thuộc người được gán; log mới nhất trỏ đúng người được gán. */
    private void assertInvariants() {
        assertThat(jdbc.queryForList("""
                SELECT v.id FROM visits v JOIN orders o ON o.visit_id = v.id JOIN order_lines l ON l.order_id = o.id
                WHERE v.branch_id = ? AND v.status = 'WAITING' AND v.assignee_id IS NOT NULL AND l.is_auto_generated
                  AND l.owner_account_id <> v.assignee_id""", Long.class, branch)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT v.id FROM visits v
                WHERE v.branch_id = ? AND EXISTS (SELECT 1 FROM visit_assignments a WHERE a.visit_id = v.id)
                  AND v.assignee_id <> (SELECT a.to_account_id FROM visit_assignments a WHERE a.visit_id = v.id
                                        ORDER BY a.id DESC LIMIT 1)""", Long.class, branch)).isEmpty();
    }

    private long walkIn(long serviceId, Instant at) {
        return walkInAt(branch, serviceId, at);
    }

    private long walkInAt(long branchId, long serviceId, Instant at) {
        String bearer = branchId == branch ? receptionistToken : token(data.staff("RECEPTIONIST", branchId));
        clock.set(at);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", data.pet(customer));
        body.put("serviceId", serviceId);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, bearer, "/api/visits", body), HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    /** Lịch hẹn MEDICAL hôm nay ở {@code slot}, check-in lúc {@code arrival} (trong cửa sổ, giữ ưu tiên). */
    private long appointmentVisit(LocalTime slot, Instant arrival) {
        long pet = data.pet(customer);
        long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", TODAY, slot, "BOOKED",
                receptionist);
        clock.set(arrival);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", body),
                HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    private long product() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "IT dispatch " + UUID.randomUUID());
        categories.add(category);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, unit, price)
                VALUES (?, ?, 'Pate', 'GOODS', 'hộp', 30000) RETURNING id""", Long.class, category,
                "IT-" + UUID.randomUUID().toString().substring(0, 12));
        products.add(id);
        return id;
    }

    private long orderOf(long visitId) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visitId);
    }

    /** Cột của ba loại dòng: dòng tự sinh, dòng dịch vụ thêm tay, dòng hàng. */
    private Map<String, Long> column(long orderId, String column) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT line_type, is_auto_generated, " + column
                + " AS value FROM order_lines WHERE order_id = ?", orderId)) {
            String kind = Boolean.TRUE.equals(row.get("is_auto_generated")) ? "auto"
                    : "GOODS".equals(row.get("line_type")) ? "goods" : "service";
            result.put(kind, ((Number) row.get("value")).longValue());
        }
        return result;
    }

    private long lineId(long orderId, String kind) {
        return jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ? AND is_auto_generated = ?",
                Long.class, orderId, "auto".equals(kind));
    }

    private Map<String, Long> xmins(long visitId) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("visit", jdbc.queryForObject("SELECT xmin::text::bigint FROM visits WHERE id = ?", Long.class,
                visitId));
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

    private Instant key(long visitId) {
        return jdbc.queryForObject("SELECT queue_sort_key FROM visits WHERE id = ?", Timestamp.class, visitId)
                .toInstant();
    }

    private String priority(long visitId) {
        return jdbc.queryForObject("SELECT priority_class FROM visits WHERE id = ?", String.class, visitId);
    }

    private String staffName(long accountId) {
        return jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class,
                accountId);
    }

    private long activeBranch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
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

    /**
     * Một transaction khác giữ khóa ({@code lockSql}); các request bắn song song phải cùng đứng chờ khóa
     * ({@code pg_stat_activity}, {@code waitingLike}, đủ {@code expectedWaiting}); rồi bên giữ khóa chạy
     * {@code beforeRelease} trong chính transaction của nó và commit.
     */
    private List<ResponseEntity<Map<String, Object>>> whileHeld(String lockSql, Object[] lockArgs, String waitingLike,
            java.util.function.Consumer<JdbcTemplate> beforeRelease,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests, int expectedWaiting) throws Exception {
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

    private ResponseEntity<Map<String, Object>> assign(String bearer, long visitId, long assigneeId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/assign",
                Map.of("assigneeId", assigneeId));
    }

    private ResponseEntity<Map<String, Object>> move(String bearer, long visitId, long beforeId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/move",
                Map.of("placeBeforeVisitId", beforeId));
    }

    private ResponseEntity<Map<String, Object>> emergency(String bearer, long visitId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/mark-emergency", null);
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, String path) {
        return exchange(HttpMethod.GET, bearer, path, null);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        return http.exchange(URI.create(http.getRootUri() + path), method,
                new HttpEntity<>(body, headers(bearer, MediaType.APPLICATION_JSON)), MAP);
    }

    private ResponseEntity<Map<String, Object>> rawAssign(String bearer, String path, String body) {
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST,
                new HttpEntity<>(body, headers(bearer, MediaType.APPLICATION_JSON)), MAP);
    }

    private static HttpHeaders headers(String bearer, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(type);
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

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitDispatchIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
