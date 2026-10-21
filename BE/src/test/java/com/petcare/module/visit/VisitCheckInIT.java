package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.support.MutableClock;

/**
 * {@code POST /api/visits} (visit-v1 #1, Visit#1 + Lịch hẹn#3 + Order#1; docs/adr/0059) trên Postgres 17 thật.
 * <ul>
 * <li>Đường đúng: lịch hẹn / walk-in / cấp cứu ngoài giờ — response so với DB, mọi bảng phải có / không được có (F2).</li>
 * <li>Mọi rule (D4), thứ tự lỗi (D5), ma trận đầu vào (D3), vai trò và phiên (A7).</li>
 * <li>Rollback (F5 T7–T9), race (L3 I1–I3), tác động lên module khác (I-F).</li>
 * </ul>
 * Đồng hồ {@link MutableClock} ở <b>năm 2004</b> (chưa IT nào dùng): bộ đếm {@code ('visits' | 'ORDER', '0410')} riêng.
 * {@code T0} = 2004-10-15 09:00 giờ Việt Nam; chi nhánh mở 08:00–12:00 và 14:00–19:00 mọi ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitCheckInIT.TestBeans.class})
class VisitCheckInIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2004, 10, 15);
    private static final Instant T0 = at(9, 0);
    private static final LocalTime SLOT = LocalTime.of(9, 30);
    private static final String PERIOD = "0410";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String[] VISIT_KEYS = {"visitId", "code", "branchId", "customerId", "customerName", "petId",
        "petName", "species", "appointmentId", "serviceGroup", "status", "priorityClass", "queueSortKey", "assigneeId",
        "assigneeName", "needsReassign", "orderId", "checkedInAt", "calledAt", "completedAt", "cancelledAt",
        "cancelReason"};

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Đánh dấu thú mất (I-F) gọi {@code isPetInStay}; bản placeholder ném lỗi (D033). */
    @MockitoBean
    private BoardingQueryApi boarding;

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
    private VisitQueryApi visitQueries;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private long branch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long customer;
    private long pet;
    private long appointment;
    private String token;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        when(boarding.isPetInStay(anyLong())).thenReturn(false);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        data.branchService(branch, medical, true);
        data.branchService(branch, grooming, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer("Khách tiếp nhận " + UUID.randomUUID(), null);
        pet = data.pet(customer);
        appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", TODAY, SLOT, "BOOKED", receptionist);
        token = token(receptionist);
    }

    @AfterEach
    void tearDown() {
        for (long b : branches) {
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        data.cleanUp();
    }

    // ============================================================ đường đúng + F2 (phải có / không được có)

    @Test
    void appointmentCheckInPersistsVisitAppointmentAndOrderTogether() {
        Snapshot before = snapshot();

        ResponseEntity<Map<String, Object>> response = post(token, body(pet, appointment, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code").containsEntry("code", 201)
                .containsEntry("message", "Đã tiếp nhận");
        Map<String, Object> visit = data(response);
        assertThat(visit).containsOnlyKeys(VISIT_KEYS);
        long visitId = ((Number) visit.get("visitId")).longValue();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM visits WHERE id = ?", visitId);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE visit_id = ?", Long.class, visitId);

        assertThat((String) visit.get("code")).matches("^TN-0410-\\d{5}$").isEqualTo(row.get("code"));
        assertThat(visit).containsEntry("branchId", (int) branch).containsEntry("customerId", (int) customer)
                .containsEntry("petId", (int) pet).containsEntry("petName", "Milu").containsEntry("species", "DOG")
                .containsEntry("appointmentId", (int) appointment).containsEntry("serviceGroup", "MEDICAL")
                .containsEntry("status", "WAITING").containsEntry("priorityClass", "APPOINTMENT")
                .containsEntry("assigneeId", null).containsEntry("assigneeName", null)
                .containsEntry("needsReassign", false).containsEntry("orderId", (int) orderId)
                .containsEntry("calledAt", null).containsEntry("completedAt", null)
                .containsEntry("cancelledAt", null).containsEntry("cancelReason", null);
        assertThat(visit.get("customerName"))
                .isEqualTo(jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, customer));
        assertThat(Instant.parse((String) visit.get("checkedInAt")))
                .isEqualTo(((Timestamp) row.get("checked_in_at")).toInstant()).isEqualTo(T0);
        assertThat(Instant.parse((String) visit.get("queueSortKey"))).as("giờ hẹn").isEqualTo(at(9, 30))
                .isEqualTo(((Timestamp) row.get("queue_sort_key")).toInstant());
        assertThat(row).containsEntry("checked_in_by", receptionist);

        assertThat(appointmentStatus(appointment)).isEqualTo("CHECKED_IN");
        assertThat(jdbc.queryForMap("SELECT source, status, customer_id, branch_id, created_by, total_amount "
                + "FROM orders WHERE id = ?", orderId)).containsEntry("source", "VISIT").containsEntry("status", "OPEN")
                .containsEntry("customer_id", customer).containsEntry("branch_id", branch)
                .containsEntry("created_by", receptionist).containsEntry("total_amount", 150_000L);
        assertThat(jdbc.queryForList("SELECT line_type, service_id, quantity, unit_price, is_auto_generated, added_by, "
                + "owner_account_id FROM order_lines WHERE order_id = ?", orderId)).singleElement()
                .satisfies(line -> assertThat(line).containsEntry("line_type", "SERVICE")
                        .containsEntry("service_id", medical).containsEntry("quantity", 1)
                        .containsEntry("unit_price", 150_000L).containsEntry("is_auto_generated", true)
                        .containsEntry("added_by", receptionist).containsEntry("owner_account_id", receptionist));

        Snapshot after = snapshot();
        assertThat(after.visitCounter()).isEqualTo(before.visitCounter() + 1);
        assertThat(after.orderCounter()).isEqualTo(before.orderCounter() + 1);
        assertThat(after.audits()).as("không audit (08 §8.3)").isEqualTo(before.audits());
        assertThat(after.outbox()).as("không thông báo").isEqualTo(before.outbox());
        assertThat(after.restrictions()).as("không phải vi phạm BR-LH-09").isEqualTo(before.restrictions());
        assertThat(after.petXmin()).as("chỉ khóa, không ghi pets").isEqualTo(before.petXmin());
        assertThat(after.branchXmin()).as("không ghi branches").isEqualTo(before.branchXmin());
        assertInvariants();
    }

    @Test
    void walkInPersistsVisitAndOrderWithThePriceSnapshotAtCheckIn() {
        Map<String, Object> visit = data(post(token, body(pet, null, grooming, null)), HttpStatus.CREATED);

        assertThat(visit).containsEntry("appointmentId", null).containsEntry("serviceGroup", "GROOMING")
                .containsEntry("priorityClass", "WALK_IN");
        assertThat(Instant.parse((String) visit.get("queueSortKey"))).isEqualTo(T0);
        assertThat(appointmentStatus(appointment)).as("lịch của thú không bị đụng").isEqualTo("BOOKED");
        long orderId = ((Number) visit.get("orderId")).longValue();

        jdbc.update("UPDATE services SET price = 999000 WHERE id = ?", grooming);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_lines WHERE order_id = ?", Long.class, orderId))
                .as("BR-BH-03 snapshot").isEqualTo(150_000L);
        assertInvariants();
    }

    @Test
    void customerWithTwoPetsGetsTwoVisits() {
        long secondPet = data.pet(customer);

        post(token, body(pet, null, medical, null));
        post(token, body(secondPet, null, grooming, null));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM visits WHERE customer_id = ?", Long.class, customer))
                .isEqualTo(2);
    }

    @Test
    void latenessUpToTheLimitKeepsPriorityAndBeyondIsWalkIn() {
        clock.set(at(9, 45));
        assertThat(data(post(token, body(pet, appointment, null, null)), HttpStatus.CREATED))
                .containsEntry("priorityClass", "APPOINTMENT");

        long otherPet = data.pet(customer);
        long late = data.appointment(customer, otherPet, branch, grooming, "GROOMING", TODAY, SLOT, "BOOKED",
                receptionist);
        clock.set(at(9, 45).plusNanos(1_000));
        Map<String, Object> visit = data(post(token, body(otherPet, late, null, null)), HttpStatus.CREATED);
        assertThat(visit).containsEntry("priorityClass", "WALK_IN");
        assertThat(Instant.parse((String) visit.get("queueSortKey"))).isEqualTo(at(9, 45).plusNanos(1_000));
    }

    @Test
    void emergencyInOpeningHoursIsEmergencyEvenWithAnAppointment() {
        Map<String, Object> visit = data(post(token, body(pet, appointment, null, true)), HttpStatus.CREATED);

        assertThat(visit).containsEntry("priorityClass", "EMERGENCY");
        assertThat(Instant.parse((String) visit.get("queueSortKey"))).isEqualTo(T0);
    }

    @Test
    void afterHoursOnlyEmergenciesAtAFlaggedBranch() {
        clock.set(at(12, 0));
        assertRule(post(token, body(pet, null, medical, true)), "BR-CN-05",
                "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)");

        jdbc.update("UPDATE branches SET accepts_after_hours_emergency = true WHERE id = ?", branch);
        assertRule(post(token, body(pet, null, medical, false)), "BR-CN-05",
                "Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05)");
        assertThat(data(post(token, body(pet, null, medical, true)), HttpStatus.CREATED))
                .containsEntry("priorityClass", "EMERGENCY");
    }

    @Test
    void openingHoursApplyToEveryCheckInIncludingEarlyAppointments() {
        long early = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", TODAY,
                LocalTime.of(8, 0), "BOOKED", receptionist);
        long earlyPet = jdbc.queryForObject("SELECT pet_id FROM appointments WHERE id = ?", Long.class, early);
        clock.set(at(7, 40));
        assertRule(post(token, body(earlyPet, early, null, null)), "BR-CN-05",
                "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)");
        assertThat(appointmentStatus(early)).isEqualTo("BOOKED");

        clock.set(at(13, 45));
        assertRule(post(token, body(pet, null, medical, null)), "BR-CN-05",
                "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)");

        clock.set(T0);
        data.holiday(branch, TODAY, receptionist);
        assertRule(post(token, body(pet, null, medical, null)), "BR-CN-05",
                "Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ (BR-CN-05)");
    }

    // ============================================================ rule (D4) và trạng thái lịch

    @Test
    void guardRejectionsWriteNothing() {
        Snapshot before = snapshot();

        assertRule(post(token, body(pet, null, null, null)), "BR-TN-01",
                "Khách không hẹn trước phải chọn dịch vụ (BR-TN-01)");
        long otherPet = data.pet(customer);
        assertRule(post(token, body(otherPet, appointment, null, null)), "BR-TN-01",
                "Thú cưng không khớp lịch hẹn (BR-TN-01)");
        long boardingService = data.service("BOARDING");
        assertRule(post(token, body(pet, null, boardingService, null)), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");
        long disabled = data.service("MEDICAL");
        data.branchService(branch, disabled, false);
        assertRule(post(token, body(pet, null, disabled, null)), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", grooming);
        assertRule(post(token, body(pet, null, grooming, null)), "BR-LH-01",
                "Dịch vụ không tiếp nhận được tại chi nhánh này (BR-LH-01)");
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY, pet);
        assertRule(post(token, body(pet, null, medical, null)), "BR-TN-01",
                "Thú cưng đã mất, không tiếp nhận được (BR-TN-01)");

        assertNothingChanged(before);
    }

    @Test
    void draftBranchCannotCheckIn() {
        long draft = trackBranch(data.branch("DRAFT"));
        data.openingHours(draft, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
        data.branchService(draft, medical, true);
        String draftToken = token(data.staff("RECEPTIONIST", draft));

        assertRule(post(draftToken, body(pet, null, medical, null)), "BR-CN-01",
                "Chi nhánh chưa hoạt động, chưa tiếp nhận được (BR-CN-01)");
    }

    @Test
    void appointmentOfAnotherBranchOrOutsideTheWindowIsTn02() {
        long other = activeBranch();
        long foreignPet = data.pet(customer);
        long foreign = data.appointment(customer, foreignPet, other, medical, "MEDICAL", TODAY, SLOT, "BOOKED",
                receptionist);
        long foreignXmin = xmin("appointments", foreign);
        assertRule(post(token, body(foreignPet, foreign, null, null)), "BR-TN-02",
                "Lịch hẹn thuộc chi nhánh khác (BR-TN-02)");
        assertThat(xmin("appointments", foreign)).isEqualTo(foreignXmin);

        long tooEarly = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", TODAY,
                LocalTime.of(11, 0), "BOOKED", receptionist);
        long tooEarlyPet = jdbc.queryForObject("SELECT pet_id FROM appointments WHERE id = ?", Long.class, tooEarly);
        ResponseEntity<Map<String, Object>> response = post(token, body(tooEarlyPet, tooEarly, null, null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TN-02)");
        assertThat(appointmentStatus(tooEarly)).isEqualTo("BOOKED");
    }

    @Test
    void appointmentNotBookedIs409AndNotFoundIs404() {
        for (String status : List.of("NO_SHOW", "CANCELLED", "CHECKED_IN")) {
            long done = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", TODAY, SLOT, status,
                    receptionist);
            long donePet = jdbc.queryForObject("SELECT pet_id FROM appointments WHERE id = ?", Long.class, done);
            ResponseEntity<Map<String, Object>> response = post(token, body(donePet, done, null, null));
            assertThat(response.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("message", "Không thể chuyển trạng thái lịch hẹn từ " + status + " sang CHECKED_IN");
        }
        assertError(post(token, body(pet, Long.MAX_VALUE, null, null)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post(token, body(Long.MAX_VALUE, appointment, null, null)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(post(token, body(pet, null, Long.MAX_VALUE, null)), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(appointmentStatus(appointment)).isEqualTo("BOOKED");
    }

    @Test
    void appointmentServiceDisabledAtTheBranchIsCheckedInButStoppedIsRejected() {
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                medical);
        assertThat(post(token, body(pet, appointment, null, null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long otherPet = data.pet(customer);
        long stopped = data.appointment(customer, otherPet, branch, grooming, "GROOMING", TODAY, SLOT, "BOOKED",
                receptionist);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", grooming);
        assertRule(post(token, body(otherPet, stopped, null, null)), "BR-LH-01",
                "Dịch vụ của lịch hẹn đã ngừng kinh doanh (BR-LH-01)");
        assertThat(appointmentStatus(stopped)).isEqualTo("BOOKED");
    }

    @Test
    void serviceIdIsIgnoredWhenAnAppointmentIsGiven() {
        Map<String, Object> visit = data(post(token, body(pet, appointment, grooming, null)), HttpStatus.CREATED);

        assertThat(visit).containsEntry("serviceGroup", "MEDICAL");
        long orderId = ((Number) visit.get("orderId")).longValue();
        assertThat(jdbc.queryForObject("SELECT service_id FROM order_lines WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(medical);
    }

    // ============================================================ thứ tự lỗi (D5)

    @Test
    void precedenceFollowsPermissionNotFoundRuleConflict() {
        long draft = trackBranch(data.branch("DRAFT"));
        String draftToken = token(data.staff("RECEPTIONIST", draft));
        assertError(post(draftToken, body(pet, Long.MAX_VALUE, null, null)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");

        long other = activeBranch();
        long foreign = data.appointment(customer, data.pet(customer), other, medical, "MEDICAL", TODAY, SLOT, "BOOKED",
                receptionist);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", medical);
        assertRule(post(token, body(pet, foreign, null, null)), "BR-TN-02", "Lịch hẹn thuộc chi nhánh khác (BR-TN-02)");

        long noShow = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", TODAY, SLOT,
                "NO_SHOW", receptionist);
        long noShowPet = jdbc.queryForObject("SELECT pet_id FROM appointments WHERE id = ?", Long.class, noShow);
        assertRule(post(token, body(noShowPet, noShow, null, null)), "BR-LH-01",
                "Dịch vụ của lịch hẹn đã ngừng kinh doanh (BR-LH-01)");
        jdbc.update("UPDATE services SET is_active = true WHERE id = ?", medical);

        long draftNoShow = data.appointment(customer, noShowPet, draft, medical, "MEDICAL", TODAY, SLOT, "NO_SHOW",
                receptionist);
        assertRule(post(draftToken, body(noShowPet, draftNoShow, null, null)), "BR-CN-01",
                "Chi nhánh chưa hoạt động, chưa tiếp nhận được (BR-CN-01)");
    }

    // ============================================================ ma trận đầu vào (D3)

    @Test
    void inputMatrix() {
        Snapshot before = snapshot();
        assertError(raw(HttpMethod.POST, token, null, MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.POST, token, "{", MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.POST, token, "[]", MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(raw(HttpMethod.POST, token, "{}", MediaType.APPLICATION_JSON).getBody())
                .containsEntry("errorCode", "VALIDATION_FAILED").containsEntry("message", "petId: Thiếu thú cưng");
        assertThat(raw(HttpMethod.POST, token, "{\"petId\": 0}", MediaType.APPLICATION_JSON).getBody())
                .containsEntry("message", "petId: Mã thú cưng không hợp lệ");
        assertError(raw(HttpMethod.POST, token, "{\"petId\": 1.5}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.POST, token, "{\"petId\": \"abc\"}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.POST, token, "{\"petId\": 9223372036854775808}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(raw(HttpMethod.POST, token, "{\"petId\": 1, \"appointmentId\": 0, \"serviceId\": -3}",
                MediaType.APPLICATION_JSON).getBody()).containsEntry("errorCode", "VALIDATION_FAILED");
        assertError(raw(HttpMethod.POST, token, "{\"petId\": " + pet + ", \"isEmergency\": \"yes\"}",
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(HttpMethod.POST, token, "{\"petId\": " + pet + ", \"isEmergency\": {}}",
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(raw(HttpMethod.POST, token, "x", MediaType.TEXT_PLAIN).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(raw(HttpMethod.PUT, token, "{}", MediaType.APPLICATION_JSON).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertNothingChanged(before);
    }

    /** Hành vi chung của mọi trường {@code Boolean} (Jackson mặc định), ghim lại; phần này không đổi cấu hình. */
    @Test
    void emergencyFlagBindsByItsContractName() {
        long other = activeBranch();
        jdbc.update("UPDATE branches SET accepts_after_hours_emergency = true WHERE id = ?", other);
        data.branchService(other, medical, true);
        String otherToken = token(data.staff("RECEPTIONIST", other));
        clock.set(at(20, 0));

        assertThat(raw(HttpMethod.POST, otherToken, "{\"petId\": " + pet + ", \"serviceId\": " + medical
                + ", \"isEmergency\": true}", MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(raw(HttpMethod.POST, otherToken, "{\"petId\": " + pet + ", \"serviceId\": " + medical
                + ", \"emergency\": true}", MediaType.APPLICATION_JSON).getStatusCode())
                .as("tên khác hợp đồng bị bỏ qua → không cấp cứu").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(raw(HttpMethod.POST, otherToken, "{\"petId\": " + pet + ", \"serviceId\": " + medical
                + ", \"isEmergency\": \"true\"}", MediaType.APPLICATION_JSON).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(raw(HttpMethod.POST, otherToken, "{\"petId\": " + pet + ", \"serviceId\": " + medical
                + ", \"isEmergency\": 1}", MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unknownBranchFieldIsIgnoredAndTheReceptionistsBranchIsUsed() {
        long other = activeBranch();
        Map<String, Object> request = body(pet, null, medical, null);
        request.put("branchId", other);

        assertThat(data(post(token, request), HttpStatus.CREATED)).containsEntry("branchId", (int) branch);
    }

    // ============================================================ xác thực / phân quyền (A7)

    @Test
    void onlyReceptionistsMayCheckIn() {
        Snapshot before = snapshot();
        for (String role : List.of("VET", "CARETAKER", "BRANCH_MANAGER")) {
            assertError(post(token(data.staff(role, branch)), body(pet, null, medical, null)), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        for (String role : List.of("SUPER_MANAGER", "ADMIN")) {
            assertError(post(token(data.staff(role, null)), body(pet, null, medical, null)), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
        }
        assertError(post(token(data.customerAccount()), body(pet, null, medical, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        String vet = token(data.staff("VET", branch));
        assertError(post(vet, body(Long.MAX_VALUE, null, medical, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(raw(HttpMethod.POST, vet, "{}", MediaType.APPLICATION_JSON).getBody())
                .as("body sai trước vai trò").containsEntry("errorCode", "VALIDATION_FAILED");
        assertNothingChanged(before);
    }

    @Test
    void sessionAndAccountStateAreCheckedOnEveryRequest() {
        assertError(raw(HttpMethod.POST, null, "{}", MediaType.APPLICATION_JSON), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
        assertError(post("not-a-jwt", body(pet, null, medical, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        assertError(post(token, body(pet, null, medical, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long locked = data.staff("RECEPTIONIST", branch);
        String lockedToken = token(locked);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        assertError(post(lockedToken, body(pet, null, medical, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long disabled = data.staff("RECEPTIONIST", branch);
        String disabledToken = token(disabled);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        assertError(post(disabledToken, body(pet, null, medical, null)), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long mustChange = data.staff("RECEPTIONIST", branch);
        String mustChangeToken = token(mustChange);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", mustChange);
        assertThat(raw(HttpMethod.POST, mustChangeToken, "{}", MediaType.APPLICATION_JSON).getBody())
                .containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");

        long temporarilyLocked = data.staff("RECEPTIONIST", branch);
        String temporarilyLockedToken = token(temporarilyLocked);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?", Timestamp.from(T0.plusSeconds(600)),
                temporarilyLocked);
        assertThat(post(temporarilyLockedToken, body(pet, null, medical, null)).getStatusCode())
                .as("khóa tạm chỉ chặn đăng nhập").isEqualTo(HttpStatus.CREATED);

        clock.set(T0.plusSeconds(400L * 24 * 3600));
        assertError(post(temporarilyLockedToken, body(pet, null, medical, null)), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
    }

    @Test
    void roleAndBranchAreReadFromTheDatabaseOnEveryRequest() {
        long moved = data.staff("RECEPTIONIST", branch);
        String movedToken = token(moved);
        long other = activeBranch();
        data.branchService(other, medical, true);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", other, moved);
        assertThat(data(post(movedToken, body(pet, null, medical, null)), HttpStatus.CREATED))
                .containsEntry("branchId", (int) other);

        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", moved);
        assertError(post(movedToken, body(pet, null, medical, null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    // ============================================================ rollback (F5 T7–T9) và gửi lại (T12, T13)

    @Test
    void corruptOwnershipRollsBackTheAppointmentUpdate() {
        long stranger = data.counterCustomer();
        long corrupt = data.appointment(stranger, pet, branch, grooming, "GROOMING", TODAY, LocalTime.of(9, 0),
                "BOOKED", receptionist);
        long before = xmin("appointments", corrupt);

        assertThat(post(token, body(pet, corrupt, null, null)).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(appointmentStatus(corrupt)).isEqualTo("BOOKED");
        assertThat(xmin("appointments", corrupt)).isEqualTo(before);
        assertThat(visitsOfBranch()).isZero();
    }

    @Test
    void orderLineFailureRollsBackEverything() {
        String name = jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, medical);
        Snapshot before = snapshot();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("order_lines",
                "NEW.description = '" + name.replace("'", "''") + "'",
                () -> post(token, body(pet, appointment, null, null)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertNothingChanged(before);
    }

    @Test
    void visitInsertFailureRollsBackTheAppointment() {
        Snapshot before = snapshot();

        ResponseEntity<Map<String, Object>> response = withRejectingTrigger("visits", "NEW.branch_id = " + branch,
                () -> post(token, body(pet, appointment, null, null)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertNothingChanged(before);
    }

    @Test
    void retryAfterSuccessIs409AndCreatesNothing() {
        assertThat(post(token, body(pet, appointment, null, null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Snapshot before = snapshot();

        assertError(post(token, body(pet, appointment, null, null)), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");

        assertThat(snapshot().visits()).isEqualTo(before.visits());
    }

    /** Không đảm bảo (docs/adr/0059): docs không có idempotency; gửi lại walk-in tạo Visit thứ hai. */
    @Test
    void walkInRetryCreatesASecondVisit() {
        post(token, body(pet, null, medical, null));
        post(token, body(pet, null, medical, null));

        assertThat(visitsOfBranch()).isEqualTo(2);
        assertInvariants();
    }

    // ============================================================ race (L3)

    /** I1: hai lễ tân cùng check-in một lịch → đúng một 201, một 409, một Visit. */
    @Test
    void concurrentCheckInsOfOneAppointment() throws Exception {
        String second = token(data.staff("RECEPTIONIST", branch));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatus> a = pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(post(token, body(pet, appointment, null, null)).getStatusCode().value());
            });
            Future<HttpStatus> b = pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(post(second, body(pet, appointment, null, null)).getStatusCode().value());
            });
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visits WHERE appointment_id = ?", Long.class,
                appointment)).isOne();
        assertInvariants();
    }

    /** I2: đánh dấu mất đang giữ dòng thú → check-in chờ ({@code FOR SHARE}), rồi thấy thú đã mất. */
    @Test
    void checkInWaitsForDeceasedMarking() throws Exception {
        HttpStatus status = whilePetHeld(() -> jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", TODAY, pet),
                body(pet, null, medical, null));

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(visitsOfBranch()).isZero();
    }

    /** I3: chuyển chủ đang giữ dòng thú → walk-in sau đó ghi chủ mới. */
    @Test
    void walkInAfterConcurrentOwnerTransferUsesNewOwner() throws Exception {
        long newOwner = data.counterCustomer();
        HttpStatus status = whilePetHeld(() -> jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner,
                pet), body(pet, null, medical, null));

        assertThat(status).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM visits WHERE pet_id = ?", Long.class, pet))
                .isEqualTo(newOwner);
        assertThat(jdbc.queryForObject("SELECT o.customer_id FROM orders o JOIN visits v ON v.id = o.visit_id "
                + "WHERE v.pet_id = ?", Long.class, pet)).isEqualTo(newOwner);
    }

    // ============================================================ module khác (I-F)

    @Test
    void receptionistCannotEditTheAutoLineOfAnUnassignedVisit() {
        Map<String, Object> visit = data(post(token, body(pet, null, medical, null)), HttpStatus.CREATED);
        long orderId = ((Number) visit.get("orderId")).longValue();
        long lineId = jdbc.queryForObject("SELECT id FROM order_lines WHERE order_id = ?", Long.class, orderId);

        assertThat(exchange(HttpMethod.DELETE, token, "/api/orders/" + orderId + "/lines/" + lineId, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.PATCH, token, "/api/orders/" + orderId + "/lines/" + lineId,
                Map.of("quantity", 1)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> order = data(exchange(HttpMethod.GET, token, "/api/orders/" + orderId, null),
                HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> line = ((List<Map<String, Object>>) order.get("lines")).get(0);
        assertThat(line).containsEntry("canEdit", false).containsEntry("canDelete", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Long.class, orderId))
                .isOne();
        assertThat(exchange(HttpMethod.POST, token, "/api/orders/" + orderId + "/close", null).getStatusCode())
                .as("Order VISIT không chốt như bán lẻ").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void openVisitBlocksDeceasedMarkingButDoesNotLockSpeciesOrAssignTheReceptionist() {
        post(token, body(pet, appointment, null, null));

        Map<String, Object> deceased = new HashMap<>();
        deceased.put("deceasedOn", TODAY.toString());
        deceased.put("confirmed", true);
        ResponseEntity<Map<String, Object>> marked = exchange(HttpMethod.POST, token, "/api/pets/" + pet
                + "/mark-deceased", deceased);
        assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) marked.getBody().get("message")).endsWith("(BR-KH-05)");

        assertThat(data(exchange(HttpMethod.GET, token, "/api/pets/" + pet, null), HttpStatus.OK))
                .containsEntry("speciesLocked", false);
        assertThat(visitQueries.findUnfinishedVisitsAssignedTo(receptionist)).isEmpty();
    }

    @Test
    void appointmentViewShowsTheSameVisitId() {
        Map<String, Object> visit = data(post(token, body(pet, appointment, null, null)), HttpStatus.CREATED);

        Map<String, Object> view = data(exchange(HttpMethod.GET, token, "/api/appointments/" + appointment, null),
                HttpStatus.OK);
        assertThat(view).containsEntry("status", "CHECKED_IN").containsEntry("visitId", visit.get("visitId"));
    }

    // ============================================================ hỗ trợ

    private record Snapshot(long visits, long orders, int visitCounter, int orderCounter, long audits, long outbox,
            long restrictions, String appointmentStatus, long appointmentXmin, long petXmin, long branchXmin) {}

    private Snapshot snapshot() {
        return new Snapshot(visitsOfBranch(),
                jdbc.queryForObject("SELECT count(*) FROM orders WHERE branch_id = ? AND source = 'VISIT'", Long.class,
                        branch),
                counter("visits"), counter("ORDER"),
                jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                        customer),
                appointmentStatus(appointment), xmin("appointments", appointment), xmin("pets", pet),
                xmin("branches", branch));
    }

    private void assertNothingChanged(Snapshot before) {
        Snapshot after = snapshot();
        assertThat(after.visits()).isEqualTo(before.visits());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.visitCounter()).isEqualTo(before.visitCounter());
        assertThat(after.orderCounter()).isEqualTo(before.orderCounter());
        assertThat(after.audits()).isEqualTo(before.audits());
        assertThat(after.outbox()).isEqualTo(before.outbox());
        assertThat(after.restrictions()).isEqualTo(before.restrictions());
        assertThat(after.appointmentStatus()).isEqualTo(before.appointmentStatus());
        assertThat(after.appointmentXmin()).isEqualTo(before.appointmentXmin());
        assertThat(after.branchXmin()).isEqualTo(before.branchXmin());
        assertInvariants();
    }

    /** F2: mọi Visit có đúng một Order VISIT với đúng một dòng tự sinh; lịch CHECKED_IN có Visit. */
    private void assertInvariants() {
        assertThat(jdbc.queryForList("""
                SELECT v.id FROM visits v LEFT JOIN orders o ON o.visit_id = v.id AND o.source = 'VISIT'
                WHERE v.branch_id = ? AND o.id IS NULL""", Long.class, branch)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT a.id FROM appointments a LEFT JOIN visits v ON v.appointment_id = a.id
                WHERE a.branch_id = ? AND a.status = 'CHECKED_IN' AND v.id IS NULL AND a.id = ?""", Long.class, branch,
                appointment)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?
                  AND (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.is_auto_generated) <> 1""",
                Long.class, branch)).isEmpty();
    }

    private long visitsOfBranch() {
        return jdbc.queryForObject("SELECT count(*) FROM visits WHERE branch_id = ?", Long.class, branch);
    }

    private int counter(String docType) {
        List<Integer> values = jdbc.queryForList(
                "SELECT last_value FROM document_counters WHERE doc_type = ? AND period = ?", Integer.class, docType,
                PERIOD);
        return values.isEmpty() ? 0 : values.get(0);
    }

    private long xmin(String table, long id) {
        return jdbc.queryForObject("SELECT xmin::text::bigint FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private String appointmentStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private long activeBranch() {
        long id = trackBranch(data.branch("ACTIVE"));
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
    }

    private long trackBranch(long id) {
        branches.add(id);
        return id;
    }

    /** Trigger tạm từ chối INSERT (mã 23514 = check_violation), giới hạn bằng {@code condition}; luôn xóa sau đó. */
    private <T> T withRejectingTrigger(String table, String condition, java.util.function.Supplier<T> action) {
        String name = "it_reject_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'IT rejects insert' USING ERRCODE = '23514'; END $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW WHEN (" + condition
                + ") EXECUTE FUNCTION " + name + "()");
        try {
            return action.get();
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON " + table);
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    /**
     * Giữ dòng thú {@code FOR NO KEY UPDATE} trong transaction khác (như đánh dấu mất / chuyển chủ), bắn check-in, chờ
     * nó nằm chờ khóa {@code FOR SHARE} trên {@code pg_stat_activity}, chạy {@code change} rồi commit.
     */
    private HttpStatus whilePetHeld(Runnable change, Map<String, Object> request) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", pet);
                change.run();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<HttpStatus> call = pool.submit(() -> HttpStatus.valueOf(post(token, request).getStatusCode().value()));
            awaitWaitingOn("%from pets%for share%");
            assertThat(call.isDone()).as("check-in phải chờ khóa dòng thú").isFalse();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            return call.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaitingOn(String queryPattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, queryPattern);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy request chờ khóa: " + queryPattern);
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

    private static Map<String, Object> body(Long petId, Long appointmentId, Long serviceId, Boolean emergency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("appointmentId", appointmentId);
        body.put("serviceId", serviceId);
        body.put("isEmergency", emergency);
        return body;
    }

    private ResponseEntity<Map<String, Object>> post(String bearer, Map<String, Object> body) {
        return exchange(HttpMethod.POST, bearer, "/api/visits", body);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(HttpMethod method, String bearer, String body, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(type);
        return http.exchange(URI.create(http.getRootUri() + "/api/visits"), method, new HttpEntity<>(body, headers),
                MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        return data(response);
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId, String message) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION")
                .containsEntry("message", message);
        assertThat(message).endsWith("(" + ruleId + ")");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitCheckInIT")).accessToken();
    }

    private static Instant at(int hour, int minute) {
        return TODAY.atTime(hour, minute).atZone(VN).toInstant();
    }
}
