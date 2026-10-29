package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * T40.5 — đủ 6 chuyển của bảng "4. Visit" (docs/03-state-machines.md), mỗi chuyển kiểm cả hệ quả chéo trên Lịch hẹn và
 * Order (Phụ lục 03), cùng 15 cặp sai (gọi / gán / hủy / hoàn tất / gán lại từ trạng thái không cho phép) qua endpoint
 * thật, **không stand-in nào** (IT cũ của từng luồng còn mock, docs/testcases/T40-be.md §2.3). Lượt luôn có lịch hẹn
 * đặt qua {@code POST /api/appointments}; trạng thái nguồn đi bằng API, không {@code UPDATE visits.status}.
 * <p>
 * Nguồn của cặp sai luôn được gán cho {@code vetA} trước: 403 "không phải người phụ trách" đứng trước 409 (docs/adr/0069
 * §2), nên gọi / hoàn tất lượt chưa gán sẽ ra 403 chứ không phải 409 — chính là G2. Era <b>năm 2028</b>: không job nào
 * của IT khác quét tới; chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitFsmIT.TestBeans.class})
class VisitFsmIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2028, 3, 14);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(8, 30));
        }
    }

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
    private long branch;
    private long medical;
    private long vetA;
    private long vetB;
    private long customer;
    private List<Long> staff;
    private String receptionistToken;
    private String vetAToken;
    private String managerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clock.set(at(8, 30));
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        data.openingHours(branch, LocalDate.of(2028, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0),
                LocalTime.of(14, 0), LocalTime.of(19, 0));
        medical = data.service("MEDICAL");
        data.branchService(branch, medical, true);
        long receptionist = data.staff("RECEPTIONIST", branch);
        vetA = data.staff("VET", branch);
        vetB = data.staff("VET", branch);
        long manager = data.staff("BRANCH_MANAGER", branch);
        long admin = data.staff("ADMIN", null);
        staff = List.of(receptionist, vetA, vetB, manager, admin);
        customer = data.counterCustomer();
        receptionistToken = token(receptionist);
        vetAToken = token(vetA);
        managerToken = token(manager);
        adminToken = token(admin);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM visit_assignments WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)",
                branch);
        jdbc.update("""
                DELETE FROM order_lines WHERE order_id IN (
                    SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", branch);
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        for (long id : staff) {
            // khóa vetA báo BRANCH_MANAGER qua outbox (FK accounts, RESTRICT)
            jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id = ?", id);
        }
        data.cleanUp();
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — check-in: Visit {@code WAITING} chưa gán; lịch {@code CHECKED_IN}; Order {@code OPEN} kèm 1 dòng tự sinh. */
    @Test
    void checkInOpensTheVisitWithItsOrder() {
        long visit = checkedIn();

        assertThat(visit(visit)).containsEntry("status", "WAITING").containsEntry("assignee_id", null);
        assertThat(appointmentStatus(visit)).isEqualTo("CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
        assertThat(count("""
                SELECT count(*) FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? AND l.is_auto_generated""", visit)).isOne();
    }

    /** #2 — gán và gán lại lượt chưa gọi: vẫn {@code WAITING}; dòng tự sinh theo người phụ trách mới (BR-TN-01). */
    @Test
    void assigningAWaitingVisitKeepsItWaiting() {
        long visit = checkedIn();

        assign(visit, vetA);
        assign(visit, vetB);

        assertThat(visit(visit)).containsEntry("status", "WAITING").containsEntry("assignee_id", vetB);
        assertThat(count("SELECT count(*) FROM visit_assignments WHERE visit_id = ?", visit)).isEqualTo(2);
        assertThat(autoLineOwner(visit)).isEqualTo(vetB);
        assertThat(appointmentStatus(visit)).isEqualTo("CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    /** #3 — người được gán gọi lượt: {@code IN_PROGRESS}; lịch và Order không đổi. */
    @Test
    void callingStartsTheVisit() {
        long visit = called();

        assertThat(visit(visit)).containsEntry("status", "IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT called_at FROM visits WHERE id = ?", java.sql.Timestamp.class, visit))
                .isNotNull();
        assertThat(appointmentStatus(visit)).isEqualTo("CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    /**
     * #4 — gán lại lượt đã gọi: ADMIN khóa người phụ trách (cờ {@code needs_reassign}), BRANCH_MANAGER gán cho VET khác
     * (BR-TN-08). Vẫn {@code IN_PROGRESS}; chủ dòng Order chuyển theo; một audit.
     */
    @Test
    void reassigningACalledVisitAfterTheAssigneeIsLocked() {
        long visit = called();
        ok(post(adminToken, "/api/accounts/" + vetA + "/lock", Map.of("reason", "Sự cố bảo mật")));
        assertThat(visit(visit)).containsEntry("needs_reassign", true);

        ok(post(managerToken, "/api/visits/" + visit + "/reassign", Map.of("assigneeId", vetB, "reason", "VET bị khóa")));

        assertThat(visit(visit)).containsEntry("status", "IN_PROGRESS").containsEntry("assignee_id", vetB)
                .containsEntry("needs_reassign", false);
        assertThat(autoLineOwner(visit)).isEqualTo(vetB);
        assertThat(reassignAudits(visit)).isOne();
        assertThat(appointmentStatus(visit)).isEqualTo("CHECKED_IN");
        assertThat(orderStatus(visit)).isEqualTo("OPEN");
    }

    /** #5 — hoàn tất (có chẩn đoán, BR-KB-02): Visit / lịch {@code COMPLETED}, Order {@code PENDING}, bệnh án khóa. */
    @Test
    void completingClosesTheVisitTheAppointmentAndTheOrder() {
        long visit = completed();

        assertThat(visit(visit)).containsEntry("status", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT locked_at FROM medical_records WHERE visit_id = ?",
                java.sql.Timestamp.class, visit)).isNotNull();
        assertThat(appointmentStatus(visit)).isEqualTo("COMPLETED");
        assertThat(orderStatus(visit)).isEqualTo("PENDING");
    }

    /** #6 — lễ tân hủy lượt chưa gọi: lịch {@code CANCELLED} không tính hủy muộn, Order {@code CANCELLED}. */
    @Test
    void cancellingAWaitingVisitCancelsTheAppointmentAndTheOrder() {
        long visit = checkedIn();

        cancel(visit);

        assertThat(visit(visit)).containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForMap("""
                SELECT a.status, a.cancel_source, a.late_cancel FROM appointments a JOIN visits v ON v.appointment_id = a.id
                WHERE v.id = ?""", visit)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false);
        assertThat(orderStatus(visit)).isEqualTo("CANCELLED");
    }

    // ================================================================ cặp sai → 409, không ghi gì

    /**
     * 15 cặp: mỗi thao tác từ mọi trạng thái nó không được phép đi ra. Nguồn đi bằng API và đã gán {@code vetA}, để
     * người gọi / hoàn tất là người phụ trách (403 đứng trước 409) và assign / reassign trỏ tới VET hợp lệ (BR-TN-05 400
     * đứng trước) — chỉ còn trạng thái là lý do từ chối. {@code CANCELLED} có call / complete là G2.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"WAITING", "IN_PROGRESS", "COMPLETED", "CANCELLED"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long visit = reach(source);
        assertThat(visit(visit)).containsEntry("status", source);
        Map<String, Object> before = footprint(visit);

        List<String> tried = switch (source) {
            case "WAITING" -> List.of("complete", "reassign");
            case "IN_PROGRESS" -> List.of("call", "assign", "cancel");
            default -> List.of("call", "assign", "cancel", "complete", "reassign");
        };
        for (String operation : tried) {
            assertConflict(operation, attempt(operation, visit));
        }

        assertThat(footprint(visit)).isEqualTo(before);
    }

    /** Đưa một lượt mới (lịch 09:30, check-in 09:10, gán {@code vetA}) tới {@code source} chỉ bằng endpoint thật. */
    private long reach(String source) {
        long visit = checkedIn();
        assign(visit, vetA);
        switch (source) {
            case "WAITING" -> {
            }
            case "IN_PROGRESS" -> call(visit);
            case "COMPLETED" -> {
                call(visit);
                complete(visit);
            }
            case "CANCELLED" -> cancel(visit);
            default -> throw new IllegalArgumentException(source);
        }
        return visit;
    }

    private ResponseEntity<Map<String, Object>> attempt(String operation, long visit) {
        String path = "/api/visits/" + visit + "/" + operation;
        return switch (operation) {
            case "call", "complete" -> post(vetAToken, path, null);
            case "assign" -> post(receptionistToken, path, Map.of("assigneeId", vetB));
            case "cancel" -> post(receptionistToken, path, Map.of("reason", "Thử"));
            case "reassign" -> post(managerToken, path, Map.of("assigneeId", vetB, "reason", "Thử"));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    /** Dấu "không ghi gì": phiên bản dòng Visit / lịch / Order, số dòng gán / dòng Order / audit gán lại. */
    private Map<String, Object> footprint(long visit) {
        Map<String, Object> rows = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT v.xmin::text AS visit, a.xmin::text AS appointment, o.xmin::text AS order_row
                FROM visits v JOIN appointments a ON a.id = v.appointment_id JOIN orders o ON o.visit_id = v.id
                WHERE v.id = ?""", visit));
        rows.put("assignments", count("SELECT count(*) FROM visit_assignments WHERE visit_id = ?", visit));
        rows.put("lines", count("""
                SELECT count(*) FROM order_lines l JOIN orders o ON o.id = l.order_id WHERE o.visit_id = ?""", visit));
        rows.put("audits", reassignAudits(visit));
        return rows;
    }

    // ================================================================ helpers — luồng

    /** Lễ tân đặt khung 09:30 hôm nay (lúc 08:30) rồi check-in lúc 09:10 (Visit#1). */
    private long checkedIn() {
        long pet = data.pet(customer);
        clock.set(at(8, 30));
        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("petId", pet);
        booking.put("branchId", branch);
        booking.put("serviceId", medical);
        booking.put("slotDate", TODAY.toString());
        booking.put("slotStart", "09:30");
        long appointment = id(created(post(receptionistToken, "/api/appointments", booking)), "appointmentId");

        clock.set(at(9, 10));
        Map<String, Object> body = new HashMap<>();
        body.put("petId", pet);
        body.put("appointmentId", appointment);
        return id(created(post(receptionistToken, "/api/visits", body)), "visitId");
    }

    private long called() {
        long visit = checkedIn();
        assign(visit, vetA);
        call(visit);
        return visit;
    }

    private long completed() {
        long visit = called();
        complete(visit);
        return visit;
    }

    private void assign(long visit, long assignee) {
        ok(post(receptionistToken, "/api/visits/" + visit + "/assign", Map.of("assigneeId", assignee)));
    }

    private void call(long visit) {
        ok(post(vetAToken, "/api/visits/" + visit + "/call", null));
    }

    /** Ghi chẩn đoán (BR-KB-02) rồi hoàn tất bằng {@code vetA}. */
    private void complete(long visit) {
        ok(exchange(HttpMethod.PUT, vetAToken, "/api/visits/" + visit + "/medical-record",
                Map.of("examination", "Khám", "diagnosis", "Viêm da")));
        ok(post(vetAToken, "/api/visits/" + visit + "/complete", null));
    }

    private void cancel(long visit) {
        ok(post(receptionistToken, "/api/visits/" + visit + "/cancel", Map.of("reason", "Khách bỏ về")));
    }

    // ================================================================ helpers — dữ liệu

    private Map<String, Object> visit(long id) {
        return jdbc.queryForMap("SELECT status, assignee_id, needs_reassign FROM visits WHERE id = ?", id);
    }

    private String appointmentStatus(long visit) {
        return jdbc.queryForObject("""
                SELECT a.status FROM appointments a JOIN visits v ON v.appointment_id = a.id WHERE v.id = ?""",
                String.class, visit);
    }

    private String orderStatus(long visit) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE visit_id = ?", String.class, visit);
    }

    private long autoLineOwner(long visit) {
        return jdbc.queryForObject("""
                SELECT l.owner_account_id FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.visit_id = ? AND l.is_auto_generated""", Long.class, visit);
    }

    private long reassignAudits(long visit) {
        return count("""
                SELECT count(*) FROM audit_logs WHERE action = 'VISIT_REASSIGNED' AND entity_type = 'visits'
                AND entity_id = ?""", visit);
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "VisitFsmIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> post(String bearer, String path, Object body) {
        return exchange(HttpMethod.POST, bearer, path, body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private static void ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> created(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return map(response.getBody().get("data"));
    }

    private static void assertConflict(String operation, ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("errorCode", "INVALID_STATE_TRANSITION").containsEntry("statusCode", 409);
    }

    private static long id(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
