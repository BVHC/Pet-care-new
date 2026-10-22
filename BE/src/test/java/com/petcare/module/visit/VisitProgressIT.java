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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.service.OrderQueryService;
import com.petcare.module.visit.service.VisitReassignFlagService;
import com.petcare.support.MutableClock;

/**
 * visit-v1 #8 gọi lượt, #11 hủy lượt khách bỏ về và listener {@code AccountLockedEvent} (trả D017) trên Postgres thật
 * (docs/adr/0062). Mục theo plan:
 * <ul>
 * <li>Đường đúng K1, C1, C2 (phải có / không được có), JSON = GET (E), quota trả lại (BR-LH-03).</li>
 * <li>Rule BR-TN-05 / 09 / 07, thứ tự lỗi (D6), ma trận đầu vào (D3), vai trò / chéo chi nhánh (A5).</li>
 * <li>Rollback K2, K3, C3–C6 bằng trigger tạm, dữ liệu hỏng và spy.</li>
 * <li>Listener L1–L7: chỉ lượt IN_PROGRESS của người bị khóa, listener lỗi → khóa rollback, mở khóa giữ cờ (D042).</li>
 * <li>Race R1–R7: giữ khóa ở transaction khác, chờ trên {@code pg_stat_activity}.</li>
 * </ul>
 * Khóa A05–A08 hỏi {@code CashierShiftQueryApi} (placeholder ném lỗi, D016) nên bean đó được thay bằng mock trả "không
 * có ca mở" (khuôn {@code AccountLockIT}); mọi bean khác là bean thật. Đồng hồ {@link MutableClock} ở <b>năm 2008</b>
 * (chưa IT nào dùng); chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitProgressIT.TestBeans.class})
class VisitProgressIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2008, 3, 17);
    private static final Instant T0 = at(9, 0);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
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

    /** K3 / C6: bước dựng response (sau mọi lần ghi) lỗi → rollback; bean không có proxy {@code @Transactional}. */
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
    private VisitReassignFlagService reassignFlags;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> products = new ArrayList<>();
    private final List<Long> categories = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long vetA;
    private long vetB;
    private long caretaker;
    private long admin;
    private long customer;
    private String receptionistToken;
    private String vetAToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch)) {
            data.branchService(b, medical, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        data.staff("BRANCH_MANAGER", branch); // người nhận thông báo khi khóa nhân viên (BR-QT-11)
        admin = data.staff("ADMIN", null);
        customer = data.counterCustomer("Khách gọi / hủy lượt " + UUID.randomUUID(), null);
        receptionistToken = token(receptionist);
        vetAToken = token(vetA);
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

    // ================================================================ #8 gọi lượt — đường đúng (K1, E)

    @Test
    void callMovesTheVisitToInProgressAndTouchesNothingElse() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        long order = orderOf(visit);
        Map<String, Long> orderXmins = orderXmins(visit);
        long logs = logs(visit);
        long audits = count("SELECT count(*) FROM audit_logs");
        long outbox = count("SELECT count(*) FROM notification_outbox");
        Instant now = at(9, 10).plusNanos(123_456_789);
        clock.set(now);

        ResponseEntity<Map<String, Object>> response = call(vetAToken, visit);

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã gọi lượt").containsEntry("code", 200);
        Map<String, Object> json = data(response);
        assertThat(json).containsEntry("status", "IN_PROGRESS").containsEntry("assigneeId", (int) vetA)
                .containsEntry("orderId", (int) order).containsEntry("needsReassign", false)
                .containsEntry("cancelledAt", null);
        Instant micros = now.truncatedTo(ChronoUnit.MICROS);
        assertThat(Instant.parse((String) json.get("calledAt"))).isEqualTo(micros);
        assertThat(json).as("POST = GET #4").isEqualTo(data(get(receptionistToken, "/api/visits/" + visit)));

        Map<String, Object> row = visitRow(visit);
        assertThat(row).containsEntry("status", "IN_PROGRESS").containsEntry("assignee_id", vetA)
                .containsEntry("needs_reassign", false);
        assertThat(((Timestamp) row.get("called_at")).toInstant()).isEqualTo(micros);
        assertThat(logs(visit)).as("gọi lượt không ghi visit_assignments").isEqualTo(logs);
        assertThat(orderXmins(visit)).as("không đụng Order").isEqualTo(orderXmins);
        assertThat(count("SELECT count(*) FROM audit_logs")).isEqualTo(audits);
        assertThat(count("SELECT count(*) FROM notification_outbox")).isEqualTo(outbox);
        assertInvariants();
    }

    @Test
    void caretakerCallsAGroomingVisitAndMyQueueShowsIt() {
        long visit = assigned(walkIn(grooming, at(9, 0)), caretaker);
        String caretakerToken = token(caretaker);

        assertThat(call(caretakerToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(ids(list(get(caretakerToken, "/api/me/queue")))).contains(visit);
        assertThat(visitRow(visit)).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void appointmentVisitCallLeavesTheAppointmentCheckedIn() {
        long visit = assigned(appointmentVisit(LocalTime.of(9, 30), at(9, 10)), vetA);
        long appointment = appointmentOf(visit);
        long appointmentXmin = xmin("appointments", appointment);

        assertThat(call(vetAToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(xmin("appointments", appointment)).isEqualTo(appointmentXmin);
        assertThat(appointmentRow(appointment)).containsEntry("status", "CHECKED_IN");
    }

    // ================================================================ #8 — rule (D4, GU1)

    @Test
    void onlyTheAssigneeCallsAndAnUnassignedVisitCannotBeCalled() {
        long assignedToA = assigned(walkIn(medical, at(9, 0)), vetA);
        long unassigned = walkIn(medical, at(9, 1));
        Map<String, Long> before = visitXmins(assignedToA, unassigned);

        assertScopeDenied(call(token(vetB), assignedToA));
        assertScopeDenied(call(vetAToken, unassigned));

        assertThat(visitXmins(assignedToA, unassigned)).isEqualTo(before);
    }

    @Test
    void callingTwiceOrACompletedVisitIs409() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        clock.set(at(9, 5));
        assertThat(call(vetAToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);
        Instant calledAt = calledAt(visit);
        long xmin = xmin("visits", visit);

        clock.set(at(9, 6));
        assertConflict(call(vetAToken, visit),
                "Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang IN_PROGRESS");
        assertThat(xmin("visits", visit)).as("lần hai không ghi").isEqualTo(xmin);
        assertThat(calledAt(visit)).isEqualTo(calledAt);

        long completed = assigned(walkIn(medical, at(9, 7)), vetA);
        jdbc.update("UPDATE visits SET status = 'COMPLETED', called_at = now(), completed_at = now() WHERE id = ?",
                completed);
        assertConflict(call(vetAToken, completed),
                "Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang IN_PROGRESS");
    }

    /** Q4: đổi chức vụ sau khi được gán (docs/adr/0031 Q4) → 400 BR-TN-05, kể cả khi lượt đã IN_PROGRESS (400 trước 409). */
    @Test
    void callerWhoseRoleChangedAfterAssignmentIs400() {
        long waiting = assigned(walkIn(medical, at(9, 0)), vetA);
        long inProgress = assigned(walkIn(medical, at(9, 1)), vetA);
        jdbc.update("UPDATE visits SET status = 'IN_PROGRESS', called_at = now() WHERE id = ?", inProgress);
        jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", vetA);

        assertRule(call(vetAToken, waiting), WRONG_ROLE);
        assertRule(call(vetAToken, inProgress), WRONG_ROLE);
        assertThat(visitRow(waiting)).containsEntry("status", "WAITING").containsEntry("called_at", null);
    }

    /** Biên GU1 #5: khóa tạm do đăng nhập sai (`locked_until`) không làm người gọi mất hiệu lực (docs/adr/0024). */
    @Test
    void temporaryLoginLockDoesNotBlockTheCall() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(at(12, 0)), vetA);

        assertThat(call(vetAToken, visit).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================================================================ #8 — vai trò, thứ tự lỗi, đầu vào (A5, D3, D6)

    @Test
    void callRolesMatrix() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        for (long account : List.of(receptionist, data.staff("BRANCH_MANAGER", branch),
                data.staff("SUPER_MANAGER", null), admin, data.customerAccount())) {
            assertError(call(token(account), visit), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
    }

    @Test
    void callErrorPrecedence() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        long cancelledUnassigned = walkIn(medical, at(9, 1));
        assertThat(cancel(receptionistToken, cancelledUnassigned, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(call(receptionistToken, 999_999_999L), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(vetAToken, 999_999_999L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(call(vetAToken, 0L), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertScopeDenied(call(token(data.staff("VET", otherBranch)), visit));
        assertScopeDenied(call(vetAToken, cancelledUnassigned)); // không phải chủ lượt (403) trước 409
        assertThat(raw(vetAToken, HttpMethod.POST, "/api/visits/abc/call", null, MediaType.APPLICATION_JSON)
                .getBody()).containsEntry("errorCode", "MALFORMED_REQUEST")
                .containsEntry("message", "Tham số 'visitId' sai kiểu dữ liệu");
        assertThat(raw(vetAToken, HttpMethod.GET, "/api/visits/" + visit + "/call", null, MediaType.APPLICATION_JSON)
                .getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertError(call(null, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
    }

    /** #8 không khai body: body JSON hay {@code text/plain} gửi kèm đều bị bỏ qua (khuôn #7). */
    @Test
    void callIgnoresAnyBody() {
        long json = assigned(walkIn(medical, at(9, 0)), vetA);
        long text = assigned(walkIn(medical, at(9, 1)), vetA);

        assertThat(raw(vetAToken, HttpMethod.POST, "/api/visits/" + json + "/call", "{\"assigneeId\": 1}",
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw(vetAToken, HttpMethod.POST, "/api/visits/" + text + "/call", "abc", MediaType.TEXT_PLAIN)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void callerSessionAndMustChangePassword() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        assertError(call("garbage", visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vetA);
        assertThat(call(vetAToken, visit).getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION")
                .hasEntrySatisfying("message", m -> assertThat((String) m).endsWith("(BR-TK-17)"));
        jdbc.update("UPDATE accounts SET must_change_password = false, is_locked = true, locked_reason = 'IT' "
                + "WHERE id = ?", vetA);
        assertError(call(vetAToken, visit), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
    }

    // ================================================================ #8 — rollback (K2, K3)

    @Test
    void callRollsBackWhenTheUpdateIsRejected() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "visits",
                "NEW.status = 'IN_PROGRESS'", () -> call(vetAToken, visit));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING").containsEntry("called_at", null);
    }

    @Test
    void callRollsBackWhenBuildingTheResponseFails() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        doThrow(new IllegalStateException("IT: lỗi khi dựng response")).when(orderQueries)
                .findOrderIdsByVisitIds(any());

        assertError(call(vetAToken, visit), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        assertThat(visitRow(visit)).containsEntry("status", "WAITING").containsEntry("called_at", null);
    }

    // ================================================================ #11 hủy lượt — đường đúng (C1, C2, E)

    @Test
    void cancelOfAWalkInCancelsTheVisitAndItsOrderTogether() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        long order = orderOf(visit);
        long lines = count("SELECT count(*) FROM order_lines WHERE order_id = " + order);
        long audits = count("SELECT count(*) FROM audit_logs");
        long outbox = count("SELECT count(*) FROM notification_outbox");
        Instant now = at(9, 20).plusNanos(987_654_321);
        clock.set(now);

        ResponseEntity<Map<String, Object>> response = cancel(receptionistToken, visit,
                Map.of("reason", "  Khách bỏ về  "));

        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã hủy lượt").containsEntry("code", 200);
        Map<String, Object> json = data(response);
        Instant micros = now.truncatedTo(ChronoUnit.MICROS);
        assertThat(json).containsEntry("status", "CANCELLED").containsEntry("cancelReason", "Khách bỏ về")
                .containsEntry("assigneeId", (int) vetA).containsEntry("orderId", (int) order)
                .containsEntry("calledAt", null);
        assertThat(Instant.parse((String) json.get("cancelledAt"))).isEqualTo(micros);
        assertThat(json).as("POST = GET #4").isEqualTo(data(get(receptionistToken, "/api/visits/" + visit)));

        Map<String, Object> row = visitRow(visit);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "Khách bỏ về")
                .containsEntry("assignee_id", vetA);
        assertThat(((Timestamp) row.get("cancelled_at")).toInstant()).isEqualTo(micros);
        assertThat(jdbc.queryForMap("SELECT status, cancel_type, cancelled_by, cancel_reason FROM orders WHERE id = ?",
                order)).containsEntry("status", "CANCELLED").containsEntry("cancel_type", "VISIT_CANCELLED")
                .containsEntry("cancelled_by", receptionist).containsEntry("cancel_reason", null);
        assertThat(count("SELECT count(*) FROM order_lines WHERE order_id = " + order)).as("không xóa dòng")
                .isEqualTo(lines);
        assertThat(count("SELECT count(*) FROM audit_logs")).isEqualTo(audits);
        assertThat(count("SELECT count(*) FROM notification_outbox")).isEqualTo(outbox);
        assertThat(ids(list(get(receptionistToken, "/api/visits")))).as("A1: lượt hủy rời hàng đợi mặc định")
                .doesNotContain(visit);
        assertInvariants();
    }

    @Test
    void cancelOfAnAppointmentVisitCancelsTheAppointmentNotLateAndReturnsTheSlot() {
        long visit = appointmentVisit(LocalTime.of(9, 30), at(9, 10));
        long appointment = appointmentOf(visit);
        long restrictions = count("SELECT count(*) FROM booking_restrictions WHERE customer_id = " + customer);
        assertThat(booked(LocalTime.of(9, 30))).as("lịch CHECKED_IN chiếm quota").isEqualTo(1);
        clock.set(at(9, 12));

        assertThat(cancel(receptionistToken, visit, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> appointmentRow = appointmentRow(appointment);
        assertThat(appointmentRow).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false).containsEntry("cancel_reason", null);
        assertThat(appointmentRow.get("cancelled_at")).as("cùng một mốc với lượt")
                .isEqualTo(visitRow(visit).get("cancelled_at"));
        assertThat(visitRow(visit)).containsEntry("cancel_reason", null);
        assertThat(count("SELECT count(*) FROM booking_restrictions WHERE customer_id = " + customer))
                .as("không tính vi phạm BR-LH-09").isEqualTo(restrictions);
        assertThat(booked(LocalTime.of(9, 30))).as("BR-LH-03: CANCELLED trả lại quota").isZero();
        assertInvariants();
    }

    /** Sau khi hủy, lượt không còn là việc dở của người được gán (BR-QT-08), thú được đánh dấu mất / chuyển chủ. */
    @Test
    void cancelledVisitIsNoLongerPendingWork() {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        assertThat(cancel(receptionistToken, visit, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> locked = lock(vetA);

        assertThat(locked.getStatusCode()).as(String.valueOf(locked.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(pendingWork(locked)).isEmpty();
    }

    // ================================================================ #11 — đầu vào (D3)

    @Test
    void cancelBodyMatrix() {
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put(null, null);
        cases.put("{}", null);
        cases.put("{\"reason\": null}", null);
        cases.put("{\"reason\": \"   \"}", null);
        cases.put("{\"reason\": \"　\"}", null);
        cases.put("{\"reason\": \" Bỏ về \"}", "Bỏ về");
        cases.put("{\"reason\": \"" + "x".repeat(300) + "\"}", "x".repeat(300));
        cases.put("{\"reason\": \"Bỏ về\", \"status\": \"COMPLETED\"}", "Bỏ về");
        int minute = 0;
        for (Map.Entry<String, Object> c : cases.entrySet()) {
            long visit = walkIn(medical, at(9, minute++));
            ResponseEntity<Map<String, Object>> response = raw(receptionistToken, HttpMethod.POST,
                    "/api/visits/" + visit + "/cancel", c.getKey(), MediaType.APPLICATION_JSON);
            assertThat(response.getStatusCode()).as(c.getKey() + " → " + response.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(visitRow(visit)).as(c.getKey()).containsEntry("status", "CANCELLED")
                    .containsEntry("cancel_reason", c.getValue());
            assertThat(data(response)).as(c.getKey()).containsEntry("cancelReason", c.getValue());
        }
    }

    @Test
    void cancelBodyErrorsWriteNothing() {
        long visit = walkIn(medical, at(9, 0));
        Map<String, Long> before = allXmins(visit);

        ResponseEntity<Map<String, Object>> tooLong = raw(receptionistToken, HttpMethod.POST,
                "/api/visits/" + visit + "/cancel", "{\"reason\": \"" + "x".repeat(301) + "\"}",
                MediaType.APPLICATION_JSON);
        assertError(tooLong, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) tooLong.getBody().get("message")).contains("reason: Lý do tối đa 300 ký tự");
        for (String body : List.of("{", "[]", "\"x\"")) {
            assertError(raw(receptionistToken, HttpMethod.POST, "/api/visits/" + visit + "/cancel", body,
                    MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        }
        assertThat(raw(receptionistToken, HttpMethod.POST, "/api/visits/" + visit + "/cancel", "Bỏ về",
                MediaType.TEXT_PLAIN).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(raw(receptionistToken, HttpMethod.GET, "/api/visits/" + visit + "/cancel", null,
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(raw(receptionistToken, HttpMethod.POST, "/api/visits/abc/cancel", null, MediaType.APPLICATION_JSON)
                .getBody()).containsEntry("errorCode", "MALFORMED_REQUEST");
        // Bean Validation đứng trước @PreAuthorize (hành vi Spring, như RescheduleCancelIT)
        assertError(raw(vetAToken, HttpMethod.POST, "/api/visits/" + visit + "/cancel",
                "{\"reason\": \"" + "x".repeat(301) + "\"}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(allXmins(visit)).isEqualTo(before);
    }

    /** Lý do không phải chuỗi: Jackson mặc định đổi số / boolean thành chuỗi (ghim hành vi thật, không đổi cấu hình). */
    @Test
    void scalarReasonIsCoercedToText() {
        long number = walkIn(medical, at(9, 0));
        long bool = walkIn(medical, at(9, 1));

        ResponseEntity<Map<String, Object>> numeric = raw(receptionistToken, HttpMethod.POST,
                "/api/visits/" + number + "/cancel", "{\"reason\": 123}", MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> flag = raw(receptionistToken, HttpMethod.POST,
                "/api/visits/" + bool + "/cancel", "{\"reason\": true}", MediaType.APPLICATION_JSON);

        assertThat(numeric.getStatusCode()).as(String.valueOf(numeric.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(visitRow(number)).containsEntry("cancel_reason", "123");
        assertThat(flag.getStatusCode()).as(String.valueOf(flag.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(visitRow(bool)).containsEntry("cancel_reason", "true");
    }

    // ================================================================ #11 — rule, vai trò (D4, GU2, A5)

    @Test
    void calledOrFinishedVisitCannotBeCancelledAndNothingIsWritten() {
        long called = assigned(appointmentVisit(LocalTime.of(9, 30), at(9, 10)), vetA);
        assertThat(call(vetAToken, called).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> before = allXmins(called);

        assertConflict(cancel(receptionistToken, called, Map.of("reason", "Bỏ về")),
                "Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang CANCELLED");
        assertThat(allXmins(called)).isEqualTo(before);
        assertThat(appointmentRow(appointmentOf(called))).containsEntry("status", "CHECKED_IN");

        long twice = walkIn(medical, at(9, 20));
        assertThat(cancel(receptionistToken, twice, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Long> afterFirst = allXmins(twice);
        assertConflict(cancel(receptionistToken, twice, null),
                "Không thể chuyển trạng thái lượt tiếp nhận từ CANCELLED sang CANCELLED");
        assertThat(allXmins(twice)).isEqualTo(afterFirst);

        long completed = assigned(walkIn(medical, at(9, 21)), vetA);
        jdbc.update("UPDATE visits SET status = 'COMPLETED', called_at = now(), completed_at = now() WHERE id = ?",
                completed);
        assertConflict(cancel(receptionistToken, completed, null),
                "Không thể chuyển trạng thái lượt tiếp nhận từ COMPLETED sang CANCELLED");
    }

    @Test
    void cancelRolesScopeAndPrecedence() {
        long visit = walkIn(medical, at(9, 0));
        for (long account : List.of(vetA, caretaker, data.staff("BRANCH_MANAGER", branch),
                data.staff("SUPER_MANAGER", null), admin, data.customerAccount())) {
            assertError(cancel(token(account), visit, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(cancel(receptionistToken, 999_999_999L, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertScopeDenied(cancel(token(data.staff("RECEPTIONIST", otherBranch)), visit, null));
        assertError(cancel(null, visit, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
    }

    // ================================================================ #11 — rollback xuyên module (C3–C6)

    @Test
    void cancelRollsBackEverythingWhenTheOrderUpdateIsRejected() {
        long visit = appointmentVisit(LocalTime.of(9, 30), at(9, 10));

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "orders",
                "NEW.status = 'CANCELLED'", () -> cancel(receptionistToken, visit, Map.of("reason", "Bỏ về")));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING").containsEntry("cancel_reason", null);
        assertThat(appointmentRow(appointmentOf(visit))).containsEntry("status", "CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
        assertInvariants();
    }

    @Test
    void cancelRollsBackWhenTheAppointmentUpdateIsRejected() {
        long visit = appointmentVisit(LocalTime.of(9, 30), at(9, 10));

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "appointments",
                "NEW.status = 'CANCELLED'", () -> cancel(receptionistToken, visit, null));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    /** Dữ liệu hỏng: lịch hẹn không còn CHECKED_IN → appointment ném 409 sau khi lượt đã flush → rollback lượt. */
    @Test
    void cancelRollsBackWhenTheAppointmentIsNotCheckedIn() {
        long visit = appointmentVisit(LocalTime.of(9, 30), at(9, 10));
        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", appointmentOf(visit));

        assertError(cancel(receptionistToken, visit, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(visitRow(visit)).containsEntry("status", "WAITING").containsEntry("cancelled_at", null);
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    @Test
    void cancelRollsBackWhenBuildingTheResponseFails() {
        long visit = appointmentVisit(LocalTime.of(9, 30), at(9, 10));
        doThrow(new IllegalStateException("IT: lỗi khi dựng response")).when(orderQueries)
                .findOrderIdsByVisitIds(any());

        assertError(cancel(receptionistToken, visit, null), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
        assertThat(appointmentRow(appointmentOf(visit))).containsEntry("status", "CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    // ================================================================ D017 — khóa tài khoản đánh dấu cần gán lại (L1–L7)

    @Test
    void lockFlagsOnlyTheCalledVisitsOfTheLockedAccount() {
        long called = called(walkIn(medical, at(9, 0)), vetA);
        long waiting = assigned(walkIn(medical, at(9, 1)), vetA);
        long completed = assigned(walkIn(medical, at(9, 2)), vetA);
        jdbc.update("UPDATE visits SET status = 'COMPLETED', called_at = now(), completed_at = now() WHERE id = ?",
                completed);
        long otherVets = called(walkIn(medical, at(9, 3)), vetB);
        Map<String, Long> untouched = visitXmins(waiting, completed, otherVets);
        Instant now = at(10, 0).plusNanos(555_555_555);
        clock.set(now);

        ResponseEntity<Map<String, Object>> locked = lock(vetA);

        assertThat(locked.getStatusCode()).as(String.valueOf(locked.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(pendingWork(locked)).extracting(w -> w.get("type"), w -> ((Number) w.get("entityId")).longValue(),
                w -> w.get("needsReassign")).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("VISIT_IN_PROGRESS", called, true),
                org.assertj.core.groups.Tuple.tuple("VISIT_WAITING", waiting, false));
        Map<String, Object> row = visitRow(called);
        assertThat(row).containsEntry("needs_reassign", true).containsEntry("status", "IN_PROGRESS")
                .containsEntry("assignee_id", vetA);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
        assertThat(visitXmins(waiting, completed, otherVets)).as("WAITING / COMPLETED / của người khác").isEqualTo(
                untouched);

        List<Map<String, Object>> flagged = list(get(receptionistToken, "/api/visits?needsReassign=true"));
        assertThat(ids(flagged)).containsExactly(called);
        assertThat(flagged.get(0)).containsEntry("needsReassign", true);
    }

    /** Listener lỗi → cả lần khóa rollback: không khóa, phiên còn, không audit / thông báo (convention 07 §7.2). */
    @Test
    void listenerFailureRollsBackTheWholeLock() {
        long called = called(walkIn(medical, at(9, 0)), vetA);
        String reason = "IT listener " + UUID.randomUUID();
        long outbox = count("SELECT count(*) FROM notification_outbox");

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("UPDATE", "visits", "NEW.needs_reassign",
                () -> lock(vetA, reason));

        assertError(response, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vetA)).isFalse();
        assertThat(count("SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND account_id = " + vetA))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE reason = ?", Long.class, reason))
                .isZero();
        assertThat(count("SELECT count(*) FROM notification_outbox")).isEqualTo(outbox);
        assertThat(visitRow(called)).containsEntry("needs_reassign", false);
    }

    /** Q5 / D042: mở khóa không gỡ cờ; khóa lại không ghi lại dòng đã đánh dấu. */
    @Test
    void unlockKeepsTheFlagAndRelockDoesNotRewriteIt() {
        long called = called(walkIn(medical, at(9, 0)), vetA);
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        long xmin = xmin("visits", called);

        ResponseEntity<Map<String, Object>> unlocked = exchange(HttpMethod.POST, adminToken,
                "/api/accounts/" + vetA + "/unlock", Map.of("reason", "IT mở khóa"));
        assertThat(unlocked.getStatusCode()).as(String.valueOf(unlocked.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(visitRow(called)).as("D042: cờ còn sau khi mở khóa").containsEntry("needs_reassign", true);

        clock.set(at(11, 0));
        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xmin("visits", called)).as("đã đánh dấu thì không ghi lại").isEqualTo(xmin);
    }

    /** Đổi chức vụ được phép khi còn lượt (docs/adr/0031 Q4): listener không lọc theo role. */
    @Test
    void staffWhoseRoleChangedIsStillFlagged() {
        long called = called(walkIn(medical, at(9, 0)), vetA);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", vetA);

        assertThat(lock(vetA).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(visitRow(called)).containsEntry("needs_reassign", true);
    }

    @Test
    void lockOfStaffWithoutVisitsTouchesNoVisit() {
        long called = called(walkIn(medical, at(9, 0)), vetA);
        long xmin = xmin("visits", called);

        assertThat(lock(data.staff("RECEPTIONIST", branch)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(xmin("visits", called)).isEqualTo(xmin);
    }

    @Test
    void reassignFlagServiceRefusesToRunOutsideATransaction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reassignFlags.flagCalledVisitsOf(vetA))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ================================================================ race (L3)

    /** R1: khóa tài khoản đi trước — gọi lượt chờ {@code FOR SHARE} rồi thấy người gọi đã bị khóa → 400. */
    @Test
    void callWaitsForAConcurrentLockOfTheCaller() throws Exception {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(
                "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE", new Object[] {vetA},
                "%from accounts where id = % for share%",
                j -> j.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", vetA),
                List.of(() -> call(vetAToken, visit)), 1);

        assertRule(results.get(0), STAFF_INACTIVE);
        assertThat(visitRow(visit)).containsEntry("status", "WAITING");
    }

    /**
     * R2: gọi lượt đi trước — đang giữ {@code accounts FOR SHARE} (chờ dòng lượt) thì lần khóa tài khoản phải chờ, rồi
     * thấy lượt vừa gọi: {@code pendingWork} và cờ cần gán lại đều có. Bỏ khóa {@code FOR SHARE} (M4) → cờ false.
     */
    @Test
    void lockWaitsForACallHoldingTheShareLockAndFlagsTheNewlyCalledVisit() throws Exception {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
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
            Future<ResponseEntity<Map<String, Object>>> calling = pool.submit(() -> call(vetAToken, visit));
            awaitWaiting("%from visits where id = % for no key update%", 1);
            Future<ResponseEntity<Map<String, Object>>> locking = pool.submit(() -> lock(vetA));
            awaitWaiting("%from accounts%for no key update%", 1);
            assertThat(locking.isDone()).as("khóa tài khoản phải chờ FOR SHARE của lần gọi").isFalse();

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(calling.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<Map<String, Object>> locked = locking.get(30, TimeUnit.SECONDS);
            assertThat(locked.getStatusCode()).as(String.valueOf(locked.getBody())).isEqualTo(HttpStatus.OK);
            assertThat(pendingWork(locked)).singleElement().satisfies(w -> assertThat(w)
                    .containsEntry("type", "VISIT_IN_PROGRESS").containsEntry("needsReassign", true));
            assertThat(visitRow(visit)).containsEntry("status", "IN_PROGRESS").containsEntry("needs_reassign", true);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** R3: gán lại ∥ gọi lượt, hai thứ tự — kiểm chủ lượt dưới khóa dòng. */
    @Test
    void assignAndCallSerializeOnTheVisitRow() throws Exception {
        long callFirst = assigned(walkIn(medical, at(9, 0)), vetA);
        List<ResponseEntity<Map<String, Object>>> first = staged(callFirst,
                () -> call(vetAToken, callFirst), () -> assign(receptionistToken, callFirst, vetB));
        assertThat(first.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(first.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(visitRow(callFirst)).containsEntry("assignee_id", vetA).containsEntry("status", "IN_PROGRESS");

        long assignFirst = assigned(walkIn(medical, at(9, 1)), vetA);
        List<ResponseEntity<Map<String, Object>>> second = staged(assignFirst,
                () -> assign(receptionistToken, assignFirst, vetB), () -> call(vetAToken, assignFirst));
        assertThat(second.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertScopeDenied(second.get(1));
        assertThat(visitRow(assignFirst)).containsEntry("assignee_id", vetB).containsEntry("status", "WAITING");
    }

    /** R4: hủy ∥ gọi lượt, hai thứ tự. */
    @Test
    void cancelAndCallSerializeOnTheVisitRow() throws Exception {
        long callFirst = assigned(walkIn(medical, at(9, 0)), vetA);
        List<ResponseEntity<Map<String, Object>>> first = staged(callFirst,
                () -> call(vetAToken, callFirst), () -> cancel(receptionistToken, callFirst, null));
        assertThat(first.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(first.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(orderStatus(callFirst)).isEqualTo("OPEN");

        long cancelFirst = assigned(walkIn(medical, at(9, 1)), vetA);
        List<ResponseEntity<Map<String, Object>>> second = staged(cancelFirst,
                () -> cancel(receptionistToken, cancelFirst, null), () -> call(vetAToken, cancelFirst));
        assertThat(second.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(second.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(visitRow(cancelFirst)).containsEntry("status", "CANCELLED").containsEntry("called_at", null);
        assertInvariants();
    }

    /** R5: bấm đúp đồng thời — đúng một lần ghi. */
    @Test
    void doubleClickCallAndCancelWriteOnce() throws Exception {
        long visit = assigned(walkIn(medical, at(9, 0)), vetA);
        List<ResponseEntity<Map<String, Object>>> calls = staged(visit,
                () -> call(vetAToken, visit), () -> call(vetAToken, visit));
        assertThat(calls).extracting(ResponseEntity::getStatusCode)
                .containsExactly(HttpStatus.OK, HttpStatus.CONFLICT);

        long other = walkIn(medical, at(9, 1));
        List<ResponseEntity<Map<String, Object>>> cancels = staged(other,
                () -> cancel(receptionistToken, other, null), () -> cancel(receptionistToken, other, null));
        assertThat(cancels).extracting(ResponseEntity::getStatusCode)
                .containsExactly(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(orderStatus(other)).isEqualTo("CANCELLED");
    }

    /** R6, R7: lượt đã hủy không sắp lại được, Order đã hủy không thêm dòng được. */
    @Test
    void cancelledVisitCannotBeMovedNorItsOrderExtended() {
        long cancelled = walkIn(medical, at(9, 0));
        long target = walkIn(medical, at(9, 1));
        assertThat(cancel(receptionistToken, cancelled, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(exchange(HttpMethod.POST, receptionistToken, "/api/visits/" + cancelled + "/move",
                Map.of("placeBeforeVisitId", target)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(exchange(HttpMethod.POST, receptionistToken, "/api/orders/" + orderOf(cancelled) + "/lines",
                Map.of("lineType", "GOODS", "productId", product(), "quantity", 1)), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertThat(count("SELECT count(*) FROM order_lines WHERE order_id = " + orderOf(cancelled))).isEqualTo(1);
    }

    // ================================================================ helpers

    /** F4 INV-1–3 trên chi nhánh của test. */
    private void assertInvariants() {
        assertThat(jdbc.queryForList("""
                SELECT v.id FROM visits v JOIN orders o ON o.visit_id = v.id
                LEFT JOIN appointments a ON a.id = v.appointment_id
                WHERE v.branch_id = ? AND v.status = 'CANCELLED'
                  AND (o.status <> 'CANCELLED' OR (a.id IS NOT NULL AND a.status <> 'CANCELLED'))""",
                Long.class, branch)).as("INV-1").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT v.id FROM visits v JOIN orders o ON o.visit_id = v.id
                LEFT JOIN appointments a ON a.id = v.appointment_id
                WHERE v.branch_id = ? AND v.status = 'WAITING'
                  AND (o.status <> 'OPEN' OR (a.id IS NOT NULL AND a.status <> 'CHECKED_IN'))""",
                Long.class, branch)).as("INV-2").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT id FROM visits WHERE branch_id = ? AND status = 'IN_PROGRESS'
                  AND (assignee_id IS NULL OR called_at IS NULL)""", Long.class, branch)).as("INV-3").isEmpty();
    }

    /**
     * Một transaction khác giữ dòng lượt; {@code first} được gửi và phải đứng chờ trước, rồi mới gửi {@code second}
     * (cũng chờ); nhả khóa → hai request lấy khóa đúng thứ tự gửi.
     */
    private List<ResponseEntity<Map<String, Object>>> staged(long visitId,
            Callable<ResponseEntity<Map<String, Object>>> first, Callable<ResponseEntity<Map<String, Object>>> second)
            throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM visits WHERE id = ? FOR NO KEY UPDATE", visitId);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> a = pool.submit(first);
            awaitWaiting("%from visits where id = % for no key update%", 1);
            Future<ResponseEntity<Map<String, Object>>> b = pool.submit(second);
            awaitWaiting("%from visits where id = % for no key update%", 2);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Khuôn {@code VisitDispatchIT.whileHeld}: giữ khóa, đợi request đứng chờ, sửa dữ liệu rồi commit. */
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

    private long walkIn(long serviceId, Instant at) {
        clock.set(at);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", data.pet(customer));
        body.put("serviceId", serviceId);
        Map<String, Object> visit = data(exchange(HttpMethod.POST, receptionistToken, "/api/visits", body),
                HttpStatus.CREATED);
        return ((Number) visit.get("visitId")).longValue();
    }

    /** Lịch hẹn MEDICAL hôm nay ở {@code slot}, check-in lúc {@code arrival} (trong cửa sổ). */
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

    private long assigned(long visitId, long assigneeId) {
        ResponseEntity<Map<String, Object>> response = assign(receptionistToken, visitId, assigneeId);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long called(long visitId, long assigneeId) {
        assigned(visitId, assigneeId);
        String bearer = assigneeId == vetA ? vetAToken : token(assigneeId);
        ResponseEntity<Map<String, Object>> response = call(bearer, visitId);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        return visitId;
    }

    private long product() {
        long category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id",
                Long.class, "IT progress " + UUID.randomUUID());
        categories.add(category);
        long id = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, unit, price)
                VALUES (?, ?, 'Pate', 'GOODS', 'hộp', 30000) RETURNING id""", Long.class, category,
                "IT-" + UUID.randomUUID().toString().substring(0, 12));
        products.add(id);
        return id;
    }

    /** Số lịch chiếm quota của khung {@code slot} hôm nay (lễ tân xem, BR-LH-03). */
    private int booked(LocalTime slot) {
        List<Map<String, Object>> slots = list(get(receptionistToken, "/api/branches/" + branch
                + "/available-slots?serviceId=" + medical + "&date=" + TODAY));
        return slots.stream().filter(s -> slot.toString().equals(s.get("slotStart")))
                .map(s -> ((Number) s.get("booked")).intValue()).findFirst()
                .orElseThrow(() -> new AssertionError("không có khung " + slot));
    }

    private long orderOf(long visitId) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visitId);
    }

    private String orderStatus(long visitId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, visitId);
    }

    private long appointmentOf(long visitId) {
        return jdbc.queryForObject("SELECT appointment_id FROM visits WHERE id = ?", Long.class, visitId);
    }

    private Map<String, Object> visitRow(long visitId) {
        return jdbc.queryForMap("SELECT * FROM visits WHERE id = ?", visitId);
    }

    private Map<String, Object> appointmentRow(long appointmentId) {
        return jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", appointmentId);
    }

    private Instant calledAt(long visitId) {
        return jdbc.queryForObject("SELECT called_at FROM visits WHERE id = ?", Timestamp.class, visitId).toInstant();
    }

    private long xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text::bigint FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private Map<String, Long> visitXmins(long... visitIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (long id : visitIds) {
            result.put("visit#" + id, xmin("visits", id));
        }
        return result;
    }

    private Map<String, Long> orderXmins(long visitId) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("order", jdbc.queryForObject("SELECT xmin::text::bigint FROM orders WHERE visit_id = ?", Long.class,
                visitId));
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT l.id, l.xmin::text::bigint AS x FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? ORDER BY l.id""", visitId)) {
            result.put("line#" + row.get("id"), ((Number) row.get("x")).longValue());
        }
        return result;
    }

    /** {@code visits} + {@code orders} + {@code order_lines} + lịch hẹn (nếu có). */
    private Map<String, Long> allXmins(long visitId) {
        Map<String, Long> result = new LinkedHashMap<>(visitXmins(visitId));
        result.putAll(orderXmins(visitId));
        Long appointment = jdbc.queryForObject("SELECT appointment_id FROM visits WHERE id = ?", Long.class, visitId);
        if (appointment != null) {
            result.put("appointment", xmin("appointments", appointment));
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingWork(ResponseEntity<Map<String, Object>> lockResponse) {
        return (List<Map<String, Object>>) data(lockResponse).get("pendingWork");
    }

    private ResponseEntity<Map<String, Object>> lock(long accountId) {
        return lock(accountId, "IT khóa " + UUID.randomUUID());
    }

    private ResponseEntity<Map<String, Object>> lock(long accountId, String reason) {
        return exchange(HttpMethod.POST, adminToken, "/api/accounts/" + accountId + "/lock", Map.of("reason", reason));
    }

    private ResponseEntity<Map<String, Object>> call(String bearer, long visitId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/call", null);
    }

    private ResponseEntity<Map<String, Object>> cancel(String bearer, long visitId, Object body) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/cancel", body);
    }

    private ResponseEntity<Map<String, Object>> assign(String bearer, long visitId, long assigneeId) {
        return exchange(HttpMethod.POST, bearer, "/api/visits/" + visitId + "/assign",
                Map.of("assigneeId", assigneeId));
    }

    private ResponseEntity<Map<String, Object>> get(String bearer, String path) {
        return exchange(HttpMethod.GET, bearer, path, null);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        return http.exchange(URI.create(http.getRootUri() + path), method,
                new HttpEntity<>(body, headers(bearer, MediaType.APPLICATION_JSON)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String bearer, HttpMethod method, String path, String body,
            MediaType type) {
        return http.exchange(URI.create(http.getRootUri() + path), method,
                new HttpEntity<>(body, headers(bearer, type)), MAP);
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

    private static void assertConflict(ResponseEntity<Map<String, Object>> response, String message) {
        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(response.getBody()).containsEntry("message", message);
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
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitProgressIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
