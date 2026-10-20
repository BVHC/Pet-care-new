package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * Xem / gỡ hạn chế đặt online — appointment-v1 #7–9 (T25 phần 3, docs/adr/0050) và message D035, trên bean thật của
 * customer / catalog / branch; {@code BoardingQueryApi} là mock (D033) trả 0 vi phạm. Đồng hồ {@link MutableClock} năm
 * 2001 nên không đụng dữ liệu của IT khác.
 * <ul>
 * <li>Hình dạng response ≡ hợp đồng ≡ dòng SQL; {@code restricted} ≡ kết quả đặt online ở cùng thời điểm.</li>
 * <li>Validate / handler: ma trận body, path, vai trò, BR-TK-17, 401.</li>
 * <li>Commit / rollback: gỡ + audit cùng commit; lỗi audit / UPDATE rollback cả hai; 404 / 409 / đọc không ghi gì.</li>
 * <li>Đồng thời qua advisory 1002: gỡ chờ lần đánh giá và thấy hạn chế nó vừa commit (READ COMMITTED), hai lần gỡ,
 * hủy muộn ∥ gỡ theo cả hai thứ tự.</li>
 * <li>Mốc đếm sau khi gỡ (Q1 của ADR-0050, nợ D038).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BookingRestrictionIT.TestBeans.class})
class BookingRestrictionIT {

    private static final LocalDate DAY = LocalDate.of(2001, 3, 1);
    private static final Instant T0 = vn(DAY, LocalTime.of(10, 30));
    private static final String WAITING_ON_ADVISORY = "%pg_advisory_xact_lock%";
    private static final DateTimeFormatter VN_END = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
            .withZone(TimeConfig.BUSINESS_ZONE);
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
    private BoardingQueryApi boardings;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SessionService sessions;
    @Autowired
    private MutableClock clock;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long receptionist;
    private String receptionistToken;
    private long account;
    private long customer;
    private String customerToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), null, null);
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        receptionistToken = token(receptionist);
        account = data.customerAccount("restriction-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        customer = data.onlineCustomer(account, "Khách Bị Hạn Chế");
        customerToken = token(account);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ================================================================ hình dạng response

    @Test
    void responseShapeMatchesContractAndDb() {
        Instant starts = Instant.parse("2001-02-25T03:00:00.123456Z");
        Instant ends = Instant.parse("2001-03-27T03:00:00.654321Z");
        long id = restriction(customer, starts, ends, 4, null);

        Map<String, Object> mine = data(get("/api/me/booking-restriction", customerToken));
        assertThat(mine).containsOnlyKeys("restricted", "restriction").containsEntry("restricted", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> restriction = (Map<String, Object>) mine.get("restriction");
        assertThat(restriction).containsOnlyKeys("restrictionId", "customerId", "startsAt", "endsAt", "violationCount")
                .containsEntry("restrictionId", (int) id).containsEntry("customerId", (int) customer)
                .containsEntry("startsAt", "2001-02-25T03:00:00.123456Z")
                .containsEntry("endsAt", "2001-03-27T03:00:00.654321Z").containsEntry("violationCount", 4);
        assertThat(data(get("/api/customers/" + customer + "/booking-restriction", receptionistToken)))
                .as("#8 ≡ #7 cho cùng khách").isEqualTo(mine);

        long other = data.counterCustomer();
        Map<String, Object> none = data(get("/api/customers/" + other + "/booking-restriction", receptionistToken));
        assertThat(none).containsOnlyKeys("restricted", "restriction").containsEntry("restricted", false)
                .containsEntry("restriction", null);
    }

    @Test
    void statusAgreesWithTheOnlineBookingGuardAtTheBoundary() {
        Instant ends = T0.plus(Duration.ofHours(1));
        restriction(customer, T0.minus(Duration.ofDays(29)), ends, 3, null);
        long pet = data.pet(customer);
        LocalDate slotDay = DAY.plusDays(3);

        clock.set(ends.minusNanos(1_000));
        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", true);
        assertError(book(customerToken, pet, slotDay, "09:00"), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Bạn đang bị hạn chế đặt lịch online đến " + VN_END.format(ends) + ", vui lòng liên hệ chi nhánh để "
                        + "đặt lịch (BR-LH-09)");

        clock.set(ends);
        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", false);
        assertThat(book(customerToken, pet, slotDay, "09:00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ================================================================ gỡ: commit

    @Test
    void liftCommitsTheRowAndTheAuditTogetherAndReopensOnlineBooking() {
        Instant starts = T0.minus(Duration.ofDays(2));
        long id = restriction(customer, starts, starts.plus(Duration.ofDays(30)), 3, null);
        long pet = data.pet(customer);
        String reason = "it-" + UUID.randomUUID();
        clock.set(T0.plusNanos(123_456_789));
        Instant now = T0.plusNanos(123_456_000);

        ResponseEntity<Map<String, Object>> response = lift(receptionistToken, customer, "  " + reason + "  ");

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã gỡ hạn chế đặt online");
        assertThat(data(response)).containsOnlyKeys("restricted", "restriction").containsEntry("restricted", false)
                .containsEntry("restriction", null);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM booking_restrictions WHERE id = ?", id);
        assertThat(row).containsEntry("lifted_at", Timestamp.from(now)).containsEntry("lifted_by", receptionist)
                .containsEntry("lift_reason", reason)
                .containsEntry("starts_at", Timestamp.from(starts))
                .containsEntry("ends_at", Timestamp.from(starts.plus(Duration.ofDays(30))))
                .containsEntry("violation_count", 3);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).as("JPA Auditing theo Clock (ADR-0015)")
                .isBetween(now, now.plusNanos(1_000));

        List<Map<String, Object>> audits = audits(reason);
        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit).containsEntry("action", "BOOKING_RESTRICTION_LIFTED")
                .containsEntry("entity_type", "booking_restrictions").containsEntry("entity_id", id)
                .containsEntry("actor_account_id", receptionist)
                .containsEntry("actor_email", email(receptionist));
        assertThat(audit.get("before_lifted_at")).isNull();
        assertThat(audit.get("before_lifted_by")).isNull();
        assertThat(Instant.parse((String) audit.get("after_lifted_at"))).isEqualTo(now);
        assertThat(audit.get("after_lifted_by")).isEqualTo(String.valueOf(receptionist));

        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", false);
        assertThat(book(customerToken, pet, DAY.plusDays(3), "09:00").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void numericReasonIsCoercedToText() {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);

        assertThat(raw(HttpMethod.POST, liftPath(customer), receptionistToken, "{\"reason\": 5}",
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT lift_reason FROM booking_restrictions WHERE id = ?", String.class, id))
                .isEqualTo("5");
    }

    // ================================================================ 409 / 404: không ghi gì

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"none", "endsNow", "expired", "lifted", "future"})
    void notActiveIs409ForLiftAndFalseForViews(String state) {
        Long id = switch (state) {
            case "none" -> null;
            case "endsNow" -> restriction(customer, T0.minus(Duration.ofDays(30)), T0, 3, null);
            case "expired" -> restriction(customer, T0.minus(Duration.ofDays(40)), T0.minus(Duration.ofDays(10)), 3,
                    null);
            case "lifted" -> restriction(customer, T0.minus(Duration.ofDays(2)), T0.plus(Duration.ofDays(28)), 3,
                    T0.minus(Duration.ofDays(1)));
            case "future" -> restriction(customer, T0.plusSeconds(1), T0.plus(Duration.ofDays(30)), 3, null);
            default -> throw new IllegalArgumentException(state);
        };
        String before = id == null ? null : xmin(id);

        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", false)
                .containsEntry("restriction", null);
        assertThat(data(get("/api/customers/" + customer + "/booking-restriction", receptionistToken)))
                .containsEntry("restricted", false);
        assertError(lift(receptionistToken, customer, "it-" + UUID.randomUUID()), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION",
                "Không thể chuyển trạng thái hạn chế đặt online từ không còn hiệu lực sang gỡ sớm");

        if (id != null) {
            assertThat(xmin(id)).as("409 không UPDATE").isEqualTo(before);
        }
        assertThat(auditsOfCustomer()).isZero();
        assertThat(advisoryLocksHeld()).as("khóa 1002 nhả khi rollback").isZero();
    }

    @Test
    void unknownCustomerIs404ForStaffViewAndLift() {
        for (String path : List.of("/api/customers/999999999/booking-restriction",
                "/api/customers/0/booking-restriction", "/api/customers/-1/booking-restriction")) {
            assertError(get(path, receptionistToken), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
        }
        assertError(lift(receptionistToken, 999_999_999L, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy khách hàng #999999999");
        assertError(lift(receptionistToken, 0L, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy khách hàng #0");
        assertError(lift(receptionistToken, -1L, "x"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
    }

    @Test
    void viewsWriteNothing() {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        String before = xmin(id);

        data(get("/api/me/booking-restriction", customerToken));
        data(get("/api/customers/" + customer + "/booking-restriction", receptionistToken));

        assertThat(xmin(id)).isEqualTo(before);
        assertThat(auditsOfCustomer()).isZero();
    }

    // ================================================================ gỡ: rollback

    @Test
    void auditInsertFailureRollsBackTheLift() {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        long pet = data.pet(customer);

        withTrigger("audit_logs", "INSERT",
                "NEW.action = 'BOOKING_RESTRICTION_LIFTED' AND NEW.entity_id = " + id, () ->
                        assertError(lift(receptionistToken, customer, "it-" + UUID.randomUUID()),
                                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null));

        assertThat(jdbc.queryForMap("SELECT lifted_at, lifted_by, lift_reason FROM booking_restrictions WHERE id = ?",
                id)).containsEntry("lifted_at", null).containsEntry("lifted_by", null).containsEntry("lift_reason", null);
        assertThat(book(customerToken, pet, DAY.plusDays(3), "09:00").getStatusCode())
                .as("vẫn bị hạn chế").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateFailureRollsBackAndWritesNoAudit() {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);

        withTrigger("booking_restrictions", "UPDATE", "NEW.id = " + id, () ->
                assertError(lift(receptionistToken, customer, "it-" + UUID.randomUUID()),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null));

        assertThat(jdbc.queryForObject("SELECT lifted_at FROM booking_restrictions WHERE id = ?", Timestamp.class, id))
                .isNull();
        assertThat(auditsOfCustomer()).isZero();
    }

    // ================================================================ validate

    @ParameterizedTest(name = "body={0}")
    @CsvSource(delimiter = '|', value = {
            "{}                        | VALIDATION_FAILED",
            "{\"reason\": null}        | VALIDATION_FAILED",
            "{\"reason\": \"   \"}     | VALIDATION_FAILED",
            "{\"reason\": \"\\u3000\"} | VALIDATION_FAILED",
            "{\"reason\": 5            | MALFORMED_REQUEST",
            "[]                        | MALFORMED_REQUEST",
            "''                        | MALFORMED_REQUEST"})
    void invalidBodyIs400AndChangesNothing(String body, String errorCode) {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        String json = "''".equals(body) ? "" : body;

        assertError(raw(HttpMethod.POST, liftPath(customer), receptionistToken, json, MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, errorCode, null);

        assertThat(jdbc.queryForObject("SELECT lifted_at FROM booking_restrictions WHERE id = ?", Timestamp.class, id))
                .isNull();
        assertThat(auditsOfCustomer()).isZero();
    }

    @Test
    void reasonLengthAndBadPathAreValidated() {
        restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);

        ResponseEntity<Map<String, Object>> tooLong = lift(receptionistToken, customer, "a".repeat(301));
        assertError(tooLong, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) tooLong.getBody().get("message")).contains("reason").contains("Lý do tối đa 300 ký tự");
        assertError(raw(HttpMethod.POST, "/api/customers/abc/booking-restriction/lift", receptionistToken,
                "{\"reason\":\"x\"}", MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(get("/api/customers/abc/booking-restriction", receptionistToken), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertError(get("/api/customers/99999999999999999999/booking-restriction", receptionistToken),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);

        assertThat(lift(receptionistToken, customer, "đ".repeat(300)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void wrongRoleWithBadBodyIs400BecauseValidationRunsFirst() {
        long vet = data.staff("VET", branch);

        assertError(raw(HttpMethod.POST, liftPath(customer), token(vet), "{}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
    }

    @Test
    void mediaTypeMethodAndNoToken() {
        assertThat(raw(HttpMethod.POST, liftPath(customer), receptionistToken, "reason=x", MediaType.TEXT_PLAIN)
                .getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(raw(HttpMethod.PUT, liftPath(customer), receptionistToken, "{\"reason\":\"x\"}",
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(raw(HttpMethod.POST, "/api/me/booking-restriction", customerToken, "{}",
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(raw(HttpMethod.POST, "/api/customers/" + customer + "/booking-restriction", receptionistToken, "{}",
                MediaType.APPLICATION_JSON).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ================================================================ authentication

    @Test
    void everyEndpointNeedsAValidSession() {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        for (String token : new String[] {null, "garbage.token.value"}) {
            assertError(get("/api/me/booking-restriction", token), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
            assertError(get("/api/customers/" + customer + "/booking-restriction", token), HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", null);
            assertError(lift(token, customer, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }

        long loggedOut = data.staff("RECEPTIONIST", branch);
        String loggedOutToken = token(loggedOut);
        assertThat(raw(HttpMethod.POST, "/api/auth/logout", loggedOutToken, "", MediaType.APPLICATION_JSON)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertError(lift(loggedOutToken, customer, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long locked = data.staff("RECEPTIONIST", branch);
        String lockedToken = token(locked);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'it' WHERE id = ?", locked);
        assertError(lift(lockedToken, customer, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long disabled = data.staff("BRANCH_MANAGER", branch);
        String disabledToken = token(disabled);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        assertError(lift(disabledToken, customer, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        assertThat(jdbc.queryForObject("SELECT lifted_at FROM booking_restrictions WHERE id = ?", Timestamp.class, id))
                .isNull();
    }

    @Test
    void mustChangePasswordBlocksAllThreeEvenWithABadBodyOrRole() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id IN (?, ?)", receptionist, account);
        long vet = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);

        assertError(get("/api/me/booking-restriction", customerToken), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", null);
        assertError(get("/api/customers/" + customer + "/booking-restriction", receptionistToken),
                HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        ResponseEntity<Map<String, Object>> vetLift = raw(HttpMethod.POST, liftPath(customer), token(vet), "[]",
                MediaType.APPLICATION_JSON);
        assertError(vetLift, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        assertThat((String) vetLift.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    // ================================================================ authorization

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CUSTOMER", "ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void roleMatrix(String role) {
        long target = data.counterCustomer();
        long id = restriction(target, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        String token = tokenFor(role);
        boolean staffAllowed = role.equals("RECEPTIONIST") || role.equals("BRANCH_MANAGER");

        ResponseEntity<Map<String, Object>> mine = get("/api/me/booking-restriction", token);
        if (role.equals("CUSTOMER")) {
            assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        } else {
            assertError(mine, HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        }
        ResponseEntity<Map<String, Object>> view = get("/api/customers/" + target + "/booking-restriction", token);
        ResponseEntity<Map<String, Object>> lifted = lift(token, target, "it-" + UUID.randomUUID());
        if (staffAllowed) {
            assertThat(data(view)).containsEntry("restricted", true);
            assertThat(lifted.getStatusCode()).as("%s", lifted.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForObject("SELECT lifted_by FROM booking_restrictions WHERE id = ?", Long.class, id))
                    .isNotNull();
        } else {
            assertError(view, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
            assertError(lifted, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
            assertThat(jdbc.queryForObject("SELECT lifted_at FROM booking_restrictions WHERE id = ?", Timestamp.class,
                    id)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'booking_restrictions' "
                    + "AND entity_id = ?", Long.class, id)).as("403 không audit").isZero();
        }
    }

    @Test
    void roleChangeAppliesFromTheNextRequest() {
        restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        assertThat(get("/api/customers/" + customer + "/booking-restriction", receptionistToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", receptionist);

        assertError(lift(receptionistToken, customer, "x"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void customerSeesOnlyTheirOwnProfile() {
        restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        long otherAccount = data.customerAccount("restriction-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        data.onlineCustomer(otherAccount, "Khách Khác");
        String otherToken = token(otherAccount);

        assertThat(data(get("/api/me/booking-restriction", otherToken))).containsEntry("restricted", false);
        assertThat(data(get("/api/me/booking-restriction?customerId=" + customer, otherToken)))
                .as("tham số lạ bị bỏ qua").containsEntry("restricted", false);
        assertError(get("/api/customers/" + customer + "/booking-restriction", otherToken), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", null);
        assertError(lift(customerToken, customer, "tự gỡ"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void staffOfAnyBranchCanViewAndLiftChainWide() {
        long otherBranch = data.branch("ACTIVE");
        long draftBranch = data.branch("DRAFT");
        long elsewhere = data.staff("RECEPTIONIST", otherBranch);
        long draftManager = data.staff("BRANCH_MANAGER", draftBranch);
        long first = data.counterCustomer();
        long second = data.counterCustomer();
        data.appointment(first, data.pet(first), branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "NO_SHOW",
                receptionist);
        restriction(first, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        restriction(second, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);

        assertThat(data(get("/api/customers/" + first + "/booking-restriction", token(elsewhere))))
                .containsEntry("restricted", true);
        assertThat(lift(token(elsewhere), first, "it-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lift(token(draftManager), second, "it-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT lifted_by FROM booking_restrictions WHERE customer_id = ?", Long.class,
                second)).isEqualTo(draftManager);
    }

    @Test
    void customerWithAPendingLinkDecisionCanStillView() {
        restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);

        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", true);
    }

    // ================================================================ đồng thời qua advisory 1002

    /**
     * Gỡ đứng chờ advisory 1002 trong lúc một transaction (giữ vai trò {@code evaluateAfterViolation}) vừa tạo hạn chế;
     * khi nó commit, câu đọc của lần gỡ — câu mới sau khóa, READ COMMITTED — thấy dòng mới và gỡ đúng dòng đó. Đổi
     * {@code liftRestriction} sang REPEATABLE READ thì snapshot có từ câu đọc khách trước khóa → 409 (mutation làm đỏ).
     */
    @Test
    void liftWaitsForAnEvaluationAndSeesWhatItCommitted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Long[] created = new Long[1];
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                lockViolations(customer);
                created[0] = jdbc.queryForObject("""
                        INSERT INTO booking_restrictions (customer_id, starts_at, ends_at, violation_count)
                        VALUES (?, ?, ?, 3) RETURNING id""", Long.class, customer, Timestamp.from(T0),
                        Timestamp.from(T0.plus(Duration.ofDays(30))));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> lift = pool.submit(
                    () -> lift(receptionistToken, customer, "it-" + UUID.randomUUID()));
            awaitWaiting(WAITING_ON_ADVISORY, 1);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            ResponseEntity<Map<String, Object>> response = lift.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT lifted_by FROM booking_restrictions WHERE id = ?", Long.class,
                created[0])).isEqualTo(receptionist);
    }

    @Test
    void twoConcurrentLiftsLiftOnceAndAuditOnce() throws Exception {
        long id = restriction(customer, T0.minusSeconds(60), T0.plus(Duration.ofDays(30)), 3, null);
        long otherReceptionist = data.staff("RECEPTIONIST", branch);
        String otherToken = token(otherReceptionist);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<ResponseEntity<Map<String, Object>>> responses;
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                lockViolations(customer);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> first = pool.submit(
                    () -> lift(receptionistToken, customer, "it-" + UUID.randomUUID()));
            awaitWaiting(WAITING_ON_ADVISORY, 1);
            Future<ResponseEntity<Map<String, Object>>> second = pool.submit(
                    () -> lift(otherToken, customer, "it-" + UUID.randomUUID()));
            awaitWaiting(WAITING_ON_ADVISORY, 2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(responses.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(responses.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", null);
        assertThat(jdbc.queryForObject("SELECT lifted_by FROM booking_restrictions WHERE id = ?", Long.class, id))
                .isEqualTo(receptionist);
        assertThat(auditsOfCustomer()).isEqualTo(1);
    }

    /**
     * Hủy muộn thứ 3 (đánh giá BR-LH-09 dưới 1002) ∥ gỡ, theo cả hai thứ tự xếp hàng trên khóa 1002 (Postgres cấp khóa theo
     * thứ tự chờ). Hủy trước → hạn chế được tạo rồi bị gỡ ngay; gỡ trước → 409 rồi hạn chế được tạo và còn hiệu lực. Không
     * deadlock (luồng gỡ chỉ có khóa 1002).
     */
    @ParameterizedTest(name = "cancelFirst={0}")
    @ValueSource(booleans = {true, false})
    void lateCancelAndLiftSerialiseInQueueOrder(boolean cancelFirst) throws Exception {
        priorLateCancel(T0.minus(Duration.ofDays(10)));
        priorLateCancel(T0.minus(Duration.ofDays(5)));
        long late = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY,
                LocalTime.of(15, 0), "BOOKED", receptionist);
        long deadlocksBefore = deadlocks();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResponseEntity<Map<String, Object>> cancelled;
        ResponseEntity<Map<String, Object>> lifted;
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                lockViolations(customer);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> cancel;
            Future<ResponseEntity<Map<String, Object>>> lift;
            if (cancelFirst) {
                cancel = pool.submit(() -> call(HttpMethod.POST, "/api/appointments/" + late + "/cancel",
                        receptionistToken, Map.of()));
                awaitWaiting(WAITING_ON_ADVISORY, 1);
                lift = pool.submit(() -> lift(receptionistToken, customer, "it-" + UUID.randomUUID()));
            } else {
                lift = pool.submit(() -> lift(receptionistToken, customer, "it-" + UUID.randomUUID()));
                awaitWaiting(WAITING_ON_ADVISORY, 1);
                cancel = pool.submit(() -> call(HttpMethod.POST, "/api/appointments/" + late + "/cancel",
                        receptionistToken, Map.of()));
            }
            awaitWaiting(WAITING_ON_ADVISORY, 2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            cancelled = cancel.get(20, TimeUnit.SECONDS);
            lifted = lift.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> restriction = jdbc.queryForMap(
                "SELECT violation_count, lifted_at FROM booking_restrictions WHERE customer_id = ?", customer);
        assertThat(restriction).containsEntry("violation_count", 3);
        if (cancelFirst) {
            assertThat(lifted.getStatusCode()).as("%s", lifted.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(restriction.get("lifted_at")).isNotNull();
        } else {
            assertError(lifted, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", null);
            assertThat(restriction.get("lifted_at")).isNull();
        }
        assertThat(deadlocks()).isEqualTo(deadlocksBefore);
    }

    // ================================================================ mốc đếm sau khi gỡ (Q1 ADR-0050, D038)

    /** 3 vi phạm đã gây ra lần hạn chế không bị đếm lại sau khi gỡ: thêm một hủy muộn không tạo hạn chế mới. */
    @Test
    void violationsBehindALiftedRestrictionAreNotCountedAgain() {
        priorLateCancel(T0.minus(Duration.ofDays(20)));
        priorLateCancel(T0.minus(Duration.ofDays(15)));
        priorLateCancel(T0.minus(Duration.ofDays(10)));
        restriction(customer, T0.minus(Duration.ofDays(10)), T0.plus(Duration.ofDays(20)), 3, null);
        assertThat(lift(receptionistToken, customer, "it-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long late = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY,
                LocalTime.of(15, 0), "BOOKED", receptionist);

        assertThat(call(HttpMethod.POST, "/api/appointments/" + late + "/cancel", receptionistToken, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(restrictionCount()).as("chỉ còn lần đã gỡ").isEqualTo(1);
        assertThat(data(get("/api/me/booking-restriction", customerToken))).containsEntry("restricted", false);
    }

    /** Vi phạm xảy ra trong lúc bị hạn chế vẫn được đếm cho lần đánh giá sau khi gỡ (docs/adr/0050 hệ quả 2). */
    @Test
    void violationsDuringTheRestrictionCountAfterTheLift() {
        restriction(customer, T0.minus(Duration.ofDays(10)), T0.plus(Duration.ofDays(20)), 3, null);
        data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY.minusDays(8),
                LocalTime.of(9, 0), "NO_SHOW", receptionist);
        data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY.minusDays(4),
                LocalTime.of(9, 0), "NO_SHOW", receptionist);
        assertThat(lift(receptionistToken, customer, "it-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long late = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY,
                LocalTime.of(15, 0), "BOOKED", receptionist);

        assertThat(call(HttpMethod.POST, "/api/appointments/" + late + "/cancel", receptionistToken, Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(restrictionCount()).isEqualTo(2);
        Map<String, Object> mine = data(get("/api/me/booking-restriction", customerToken));
        assertThat(mine).containsEntry("restricted", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) mine.get("restriction");
        assertThat(active).containsEntry("violationCount", 3);
    }

    // ================================================================ index

    @Test
    void findActiveUsesTheCustomerEndsAtIndex() {
        String plan = tx.execute(status -> {
            jdbc.execute("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    SELECT 'IT plan ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 5000) g""");
            jdbc.execute("""
                    INSERT INTO booking_restrictions (customer_id, starts_at, ends_at, violation_count)
                    SELECT id, now() - interval '40 days', now() - interval '10 days', 3
                    FROM customers WHERE full_name LIKE 'IT plan %'""");
            jdbc.execute("ANALYZE booking_restrictions");
            String text = String.join("\n", jdbc.queryForList("""
                    EXPLAIN SELECT * FROM booking_restrictions
                    WHERE customer_id = %d AND ends_at > now() AND starts_at <= now() AND lifted_at IS NULL"""
                    .formatted(customer), String.class));
            status.setRollbackOnly();
            return text;
        });
        assertThat(plan).contains("ix_booking_restrictions_customer_ends_at");
    }

    // ================================================================ helpers

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private long restriction(long customerId, Instant startsAt, Instant endsAt, int violations, Instant liftedAt) {
        return jdbc.queryForObject("""
                INSERT INTO booking_restrictions (customer_id, starts_at, ends_at, violation_count, lifted_at, lifted_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, customerId, Timestamp.from(startsAt),
                Timestamp.from(endsAt), violations, liftedAt == null ? null : Timestamp.from(liftedAt),
                liftedAt == null ? null : receptionist);
    }

    private void priorLateCancel(Instant cancelledAt) {
        long id = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL",
                LocalDate.ofInstant(cancelledAt, TimeConfig.BUSINESS_ZONE).plusDays(1), LocalTime.of(8, 0),
                "CANCELLED", receptionist);
        jdbc.update("""
                UPDATE appointments SET late_cancel = true, cancel_source = 'CUSTOMER', cancelled_at = ? WHERE id = ?""",
                Timestamp.from(cancelledAt), id);
    }

    private String tokenFor(String role) {
        return switch (role) {
            case "CUSTOMER" -> customerToken;
            case "ADMIN", "SUPER_MANAGER" -> token(data.staff(role, null));
            default -> token(data.staff(role, branch));
        };
    }

    private void lockViolations(long customerId) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(1002, ?)", Integer.class, (int) customerId);
    }

    private int advisoryLocksHeld() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 1002 AND objid = ?""",
                Integer.class, customer);
    }

    private long deadlocks() {
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                Long.class);
    }

    private String xmin(long restrictionId) {
        return jdbc.queryForObject("SELECT xmin::text FROM booking_restrictions WHERE id = ?", String.class,
                restrictionId);
    }

    private long restrictionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                customer);
    }

    private long auditsOfCustomer() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'BOOKING_RESTRICTION_LIFTED' AND entity_id IN (
                    SELECT id FROM booking_restrictions WHERE customer_id = ?)""", Long.class, customer);
    }

    private List<Map<String, Object>> audits(String reason) {
        return jdbc.queryForList("""
                SELECT action, entity_type, entity_id, actor_account_id, actor_email,
                       before_data->>'liftedAt' AS before_lifted_at, before_data->>'liftedBy' AS before_lifted_by,
                       after_data->>'liftedAt' AS after_lifted_at, after_data->>'liftedBy' AS after_lifted_by
                FROM audit_logs WHERE reason = ?""", reason);
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_restriction_fail_" + suffix;
        String trigger = "trg_it_restriction_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, operation, table, function));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "BookingRestrictionIT")).accessToken();
    }

    private static String liftPath(long customerId) {
        return "/api/customers/" + customerId + "/booking-restriction/lift";
    }

    private ResponseEntity<Map<String, Object>> lift(String token, long customerId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return call(HttpMethod.POST, liftPath(customerId), token, body);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(HttpMethod method, String path, String token, String body,
            MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(contentType);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> book(String token, long petId, LocalDate date, String start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        return call(HttpMethod.POST, "/api/appointments", token, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String errorCode,
            String message) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp",
                        "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        if (message != null) {
            assertThat(response.getBody()).containsEntry("message", message);
        }
    }

    /** Chờ tới khi đúng {@code expected} câu lệnh (khớp {@code queryLike}) đứng chờ khóa. */
    private void awaitWaiting(String queryLike, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE ?""", Integer.class, queryLike);
            if (waiting != null && waiting == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Không thấy " + expected + " câu chờ khóa khớp " + queryLike);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
