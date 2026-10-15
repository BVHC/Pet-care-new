package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.support.MutableClock;

/**
 * T16 phần 4 — đổi khung {@code POST /api/appointments/{id}/reschedule} (#5) và hủy {@code …/cancel} (#6), cùng
 * {@code BookingRestrictionApi.evaluateAfterViolation} (UC40; BR-LH-02…07, 09; docs/adr/0045) qua HTTP trên Postgres 17
 * thật. {@code BranchQueryService}, {@code CatalogQueryService} thật; {@code PetQueryApi} / {@code CustomerQueryApi}
 * (placeholder của BE-2) thay bằng {@link JdbcPetCustomerModule}; {@code BoardingQueryApi} (placeholder) là mock — mặc
 * định 0 vi phạm lưu trú.
 * <p>
 * Đồng hồ {@code T0} = 2026-10-15 09:00 giờ Việt Nam. Chi nhánh mở 7 ngày 08:00–12:00 → khung 08:00 … 11:30. Lịch mẫu:
 * {@code DAY} = 2026-10-17 08:30, đổi sang 09:00 cùng ngày.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RescheduleCancelIT.TestBeans.class})
class RescheduleCancelIT {

    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final LocalTime START = LocalTime.of(8, 30);
    private static final List<String> APPOINTMENT_KEYS = List.of("appointmentId", "code", "customerId", "customerName",
            "petId", "petName", "branchId", "branchName", "serviceId", "serviceName", "serviceGroup", "slotDate",
            "slotStart", "status", "channel", "rescheduleCount", "lateCancel", "cancelSource", "cancelReason",
            "cancelledAt", "note", "visitId", "canReschedule", "canCancel");
    private static final String WAITING_ON_BRANCH = "%from branches%for no key update%";
    private static final String WAITING_ON_PET = "%pg_advisory_xact_lock%";
    private static final String WAITING_ON_ROW = "%from appointments%for no key update%";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        JdbcPetCustomerModule jdbcPetCustomerModule(JdbcTemplate jdbc) {
            return new JdbcPetCustomerModule(jdbc);
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

    @Autowired
    private BookingRestrictionApi restrictionApi;

    @MockitoBean
    private BoardingQueryApi boarding;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long customerAccount;
    private long customer;
    private long pet;
    private long appointment;
    private String receptionistToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch)) {
            data.openingHours(b, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
            data.branchService(b, medical, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        customerAccount = data.customerAccount();
        customer = data.onlineCustomer(customerAccount, "Nguyễn Văn An");
        pet = data.pet(customer);
        appointment = booked(pet, DAY, START);
        receptionistToken = token(receptionist);
        customerToken = token(customerAccount);
    }

    @AfterEach
    void tearDown() {
        for (String table : List.of("appointments", "booking_restrictions")) {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_" + table + " ON " + table);
            jdbc.execute("DROP FUNCTION IF EXISTS it_fail_" + table + "()");
        }
        data.cleanUp();
    }

    // ================================================================ #5 — thành công, mapping

    @Test
    void receptionistRescheduleResponseEqualsDetailAndDatabase() {
        String before = jdbc.queryForObject("SELECT code FROM appointments WHERE id = ?", String.class, appointment);

        ResponseEntity<Map<String, Object>> response = reschedule(receptionistToken, appointment, DAY, "09:00");

        Map<String, Object> data = ok(response, "Đã đổi khung giờ");
        assertThat(data).containsEntry("appointmentId", (int) appointment).containsEntry("code", before)
                .containsEntry("slotDate", "2026-10-17").containsEntry("slotStart", "09:00")
                .containsEntry("status", "BOOKED").containsEntry("rescheduleCount", 1)
                .containsEntry("petName", "Milu").containsEntry("customerName", "Nguyễn Văn An")
                .containsEntry("branchId", (int) branch).containsEntry("serviceId", (int) medical)
                .containsEntry("lateCancel", false).containsEntry("cancelSource", null)
                .containsEntry("canReschedule", true).containsEntry("canCancel", true);
        Map<String, Object> row = row(appointment);
        assertThat(row).containsEntry("slot_date", Date.valueOf(DAY)).containsEntry("reschedule_count", 1)
                .containsEntry("status", "BOOKED").containsEntry("code", before)
                .containsEntry("branch_id", branch).containsEntry("pet_id", pet).containsEntry("service_id", medical);
        assertThat(row.get("slot_start").toString()).isEqualTo("09:00:00");
        assertThat(data).isEqualTo(detail(receptionistToken, appointment));
    }

    @Test
    void customerReschedulesOwnAppointmentAndSeesTheSameAsDetail() {
        Map<String, Object> data = ok(reschedule(customerToken, appointment, DAY, "10:00"), "Đã đổi khung giờ");

        assertThat(data).containsEntry("slotStart", "10:00").containsEntry("rescheduleCount", 1);
        assertThat(data).isEqualTo(detail(customerToken, appointment));
    }

    @Test
    void rescheduleClearsTheReminderMarkSoTheNewSlotIsRemindedAgain() {
        jdbc.update("UPDATE appointments SET reminded_at = ? WHERE id = ?", Timestamp.from(T0), appointment);

        ok(reschedule(receptionistToken, appointment, DAY, "09:00"), "Đã đổi khung giờ");

        assertThat(row(appointment)).containsEntry("reminded_at", null);
    }

    @Test
    void sameSlotIsA200NoOpThatWritesNothing() {
        String xmin = xmin(appointment);

        Map<String, Object> data = ok(reschedule(customerToken, appointment, DAY, "08:30"), "Đã đổi khung giờ");

        assertThat(data).containsEntry("rescheduleCount", 0).containsEntry("slotStart", "08:30");
        assertThat(data).isEqualTo(detail(customerToken, appointment));
        assertThat(xmin(appointment)).isEqualTo(xmin);
    }

    @Test
    void bodyCannotMoveBranchPetOrServiceOrCode() {
        long otherPet = data.pet(customer);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", DAY.toString());
        body.put("slotStart", "09:00");
        body.put("branchId", otherBranch);
        body.put("petId", otherPet);
        body.put("serviceId", grooming);
        body.put("code", "LH-HACK-0001");
        body.put("channel", "ONLINE");
        body.put("rescheduleCount", 0);

        Map<String, Object> data = ok(post(receptionistToken, appointment, "reschedule", body), "Đã đổi khung giờ");

        assertThat(data).containsEntry("branchId", (int) branch).containsEntry("petId", (int) pet)
                .containsEntry("serviceId", (int) medical).containsEntry("rescheduleCount", 1)
                .containsEntry("channel", "COUNTER");
        assertThat(row(appointment)).containsEntry("branch_id", branch).containsEntry("pet_id", pet)
                .containsEntry("service_id", medical).containsEntry("channel", "COUNTER");
        assertThat((String) data.get("code")).isNotEqualTo("LH-HACK-0001");
    }

    @Test
    void rescheduleMovesQuotaOccupancyBetweenSlots() {
        assertThat(booked(slots(DAY), "08:30")).isEqualTo(1);

        ok(reschedule(receptionistToken, appointment, DAY, "09:00"), "Đã đổi khung giờ");

        List<Map<String, Object>> after = slots(DAY);
        assertThat(booked(after, "08:30")).isZero();
        assertThat(booked(after, "09:00")).isEqualTo(1);
    }

    @Test
    void lastRescheduleTurnsCanRescheduleOff() {
        jdbc.update("UPDATE appointments SET reschedule_count = 2 WHERE id = ?", appointment);

        Map<String, Object> data = ok(reschedule(receptionistToken, appointment, DAY, "09:00"), "Đã đổi khung giờ");

        assertThat(data).containsEntry("rescheduleCount", 3).containsEntry("canReschedule", false)
                .containsEntry("canCancel", true);
    }

    @Test
    void listFollowsTheNewSlot() {
        ok(reschedule(receptionistToken, appointment, DAY.plusDays(1), "09:00"), "Đã đổi khung giờ");

        assertThat(listIds(receptionistToken, "date=" + DAY)).doesNotContain(appointment);
        assertThat(listIds(receptionistToken, "date=" + DAY.plusDays(1))).contains(appointment);
    }

    // ================================================================ #5 — rule

    @Test
    void notBookedIs409BeforeAnyRule() {
        jdbc.update("""
                UPDATE appointments SET status = 'CANCELLED', cancel_source = 'STAFF', cancelled_at = ?,
                       reschedule_count = 3, slot_date = ? WHERE id = ?""", Timestamp.from(T0), Date.valueOf(TODAY),
                appointment);
        String xmin = xmin(appointment);

        assertError(reschedule(customerToken, appointment, DAY, "09:00"), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION", "Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang BOOKED");
        assertThat(xmin(appointment)).isEqualTo(xmin);
    }

    @Test
    void noTurnsLeftIsBrLh06() {
        jdbc.update("UPDATE appointments SET reschedule_count = 3 WHERE id = ?", appointment);

        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY, "09:00"),
                "Lịch hẹn đã đổi đủ 3 lần, muốn đổi nữa vui lòng hủy rồi đặt lại (BR-LH-06)");
    }

    @Test
    void customerNeedsTwelveHoursButReceptionistDoesNot() {
        long soon = booked(data.pet(customer), TODAY, LocalTime.of(20, 30));

        assertRuleWritesNothing(() -> reschedule(customerToken, soon, DAY, "09:00"),
                "Chỉ đổi được lịch trước giờ hẹn ít nhất 12 giờ, vui lòng liên hệ chi nhánh (BR-LH-06)");
        ok(reschedule(receptionistToken, soon, DAY, "09:00"), "Đã đổi khung giờ");
    }

    @Test
    void newSlotRules() {
        assertRuleWritesNothing(() -> reschedule(customerToken, appointment, TODAY.plusDays(1), "08:30"),
                "Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)");
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, TODAY, "08:30"),
                "Khung giờ đã bắt đầu (BR-LH-04)");
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY, "09:15"),
                "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)");
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY, "12:00"),
                "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)");

        data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY.plusDays(1), LocalTime.of(9, 0),
                "BOOKED", receptionist);
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY.plusDays(1), "09:00"),
                "Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)");
        data.appointment(customer, pet, otherBranch, medical, "MEDICAL", DAY.plusDays(2), LocalTime.of(11, 0),
                "COMPLETED", receptionist);
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY.plusDays(2), "09:00"),
                "Thú cưng đã có lịch Khám/Tiêm trong ngày này (BR-LH-05)");

        data.slotQuota(branch, "MEDICAL", DAY, LocalTime.of(9, 0), 0, receptionist);
        assertRuleWritesNothing(() -> reschedule(receptionistToken, appointment, DAY, "09:00"),
                "Khung giờ đã hết chỗ (BR-LH-03)");
    }

    /** Khách đang bị hạn chế đặt online vẫn tự đổi khung được (Q7). */
    @Test
    void restrictedCustomerCanStillReschedule() {
        data.restriction(customer, T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(29)), null, null);

        ok(reschedule(customerToken, appointment, DAY, "09:00"), "Đã đổi khung giờ");
    }

    // ================================================================ #6 — thành công, mapping

    @Test
    void customerCancelResponseEqualsDetailAndDatabase() {
        ResponseEntity<Map<String, Object>> response = cancel(customerToken, appointment, "{\"reason\":\"  Bận  \"}");

        Map<String, Object> data = ok(response, "Đã hủy lịch hẹn");
        assertThat(data).containsEntry("status", "CANCELLED").containsEntry("cancelSource", "CUSTOMER")
                .containsEntry("cancelReason", "Bận").containsEntry("lateCancel", false)
                .containsEntry("cancelledAt", "2026-10-15T02:00:00Z")
                .containsEntry("canReschedule", false).containsEntry("canCancel", false);
        assertThat(row(appointment)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CUSTOMER")
                .containsEntry("cancel_reason", "Bận").containsEntry("late_cancel", false)
                .containsEntry("cancelled_at", Timestamp.from(T0));
        assertThat(data).isEqualTo(detail(customerToken, appointment));
        assertThat(restrictionCount()).isZero();
    }

    @Test
    void receptionistCancelIsStaffAndBodyIsOptional() {
        long second = booked(data.pet(customer), DAY, LocalTime.of(10, 0));
        long third = booked(data.pet(customer), DAY, LocalTime.of(11, 0));

        assertThat(ok(cancel(receptionistToken, appointment, null), "Đã hủy lịch hẹn"))
                .containsEntry("cancelSource", "STAFF").containsEntry("cancelReason", null);
        assertThat(ok(cancel(receptionistToken, second, "{}"), "Đã hủy lịch hẹn")).containsEntry("cancelReason", null);
        assertThat(ok(cancel(receptionistToken, third, "{\"reason\":\"   \"}"), "Đã hủy lịch hẹn"))
                .containsEntry("cancelReason", null);
        assertThat(row(third)).containsEntry("cancel_reason", null);
    }

    @Test
    void cancelFreesTheSlotForAnotherBooking() {
        data.quotaDefault(branch, "MEDICAL", 1, receptionist);
        long otherPet = data.pet(customer);
        assertThat(book(receptionistToken, otherPet, DAY, "08:30").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ok(cancel(customerToken, appointment, null), "Đã hủy lịch hẹn");

        assertThat(book(receptionistToken, otherPet, DAY, "08:30").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void cancelledAtKeepsMicrosecondPrecisionLikeTheColumn() {
        clock.set(Instant.parse("2026-10-15T02:00:00.123456789Z"));

        Map<String, Object> data = ok(cancel(customerToken, appointment, null), "Đã hủy lịch hẹn");

        assertThat(data).containsEntry("cancelledAt", "2026-10-15T02:00:00.123456Z");
        assertThat(data).isEqualTo(detail(customerToken, appointment));
    }

    // ================================================================ #6 — rule, 409

    @Test
    void atTheAppointmentStartItIsTooLate() {
        long now = booked(data.pet(customer), TODAY, LocalTime.of(9, 0));

        assertRuleWritesNothing(() -> cancel(receptionistToken, now, null),
                "Đã tới giờ hẹn, không hủy được nữa (BR-LH-07)");
    }

    @Test
    void checkedInOrCancelledIs409() {
        jdbc.update("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?", appointment);
        assertError(cancel(receptionistToken, appointment, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Không thể chuyển trạng thái lịch hẹn từ CHECKED_IN sang CANCELLED");
        assertThat(row(appointment)).containsEntry("status", "CHECKED_IN");

        long cancelled = booked(data.pet(customer), DAY, LocalTime.of(10, 0));
        ok(cancel(customerToken, cancelled, null), "Đã hủy lịch hẹn");
        assertError(cancel(customerToken, cancelled, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang CANCELLED");
    }

    // ================================================================ hủy muộn → BR-LH-09

    @Test
    void underTwelveHoursIsLateAndExactlyTwelveIsNot() {
        long exactly = booked(data.pet(customer), TODAY, LocalTime.of(21, 0));
        long late = booked(data.pet(customer), TODAY, LocalTime.of(20, 30));

        assertThat(ok(cancel(customerToken, exactly, null), "Đã hủy lịch hẹn")).containsEntry("lateCancel", false);
        assertThat(ok(cancel(customerToken, late, null), "Đã hủy lịch hẹn")).containsEntry("lateCancel", true);
        assertThat(row(late)).containsEntry("late_cancel", true);
        assertThat(restrictionCount()).isZero();
    }

    @Test
    void thirdViolationRestrictsAndBlocksOnlineBookingButNotTheReceptionist() {
        priorLateCancel(T0.minus(Duration.ofDays(10)));
        priorNoShow(TODAY.minusDays(20));
        long late = booked(data.pet(customer), TODAY, LocalTime.of(20, 0));

        assertThat(ok(cancel(customerToken, late, null), "Đã hủy lịch hẹn")).containsEntry("lateCancel", true);

        Map<String, Object> restriction = jdbc.queryForMap(
                "SELECT * FROM booking_restrictions WHERE customer_id = ?", customer);
        assertThat(restriction).containsEntry("starts_at", Timestamp.from(T0))
                .containsEntry("ends_at", Timestamp.from(T0.plus(Duration.ofDays(30))))
                .containsEntry("violation_count", 3).containsEntry("lifted_at", null);
        assertThat(restriction.get("starts_at")).isEqualTo(row(late).get("cancelled_at"));
        assertError(book(customerToken, data.pet(customer), DAY, "10:00"), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION",
                "Bạn đang bị hạn chế đặt lịch online, vui lòng liên hệ chi nhánh để đặt lịch (BR-LH-09)");
        assertThat(book(receptionistToken, data.pet(customer), DAY, "10:00").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void secondViolationAndViolationsOutsideTheWindowDoNotRestrict() {
        priorLateCancel(T0.minus(Duration.ofDays(91)));
        priorLateCancel(T0.minus(Duration.ofDays(5)));

        ok(cancel(customerToken, booked(data.pet(customer), TODAY, LocalTime.of(20, 0)), null), "Đã hủy lịch hẹn");

        assertThat(restrictionCount()).isZero();
    }

    /** Q3: vi phạm đã gây ra lần hạn chế trước không bị đếm lại, kể cả khi lần đó đã gỡ sớm. */
    @Test
    void violationsBeforeTheLatestRestrictionAreNotRecounted() {
        priorLateCancel(T0.minus(Duration.ofDays(30)));
        priorLateCancel(T0.minus(Duration.ofDays(29)));
        data.restriction(customer, T0.minus(Duration.ofDays(28)), T0.plus(Duration.ofDays(2)),
                T0.minus(Duration.ofDays(27)), receptionist);

        ok(cancel(customerToken, booked(data.pet(customer), TODAY, LocalTime.of(20, 0)), null), "Đã hủy lịch hẹn");

        assertThat(restrictionCount()).isEqualTo(1);
    }

    /**
     * Mốc đếm loại trừ ({@code > since}): vi phạm có {@code cancelled_at} đúng bằng {@code starts_at} của lần hạn chế
     * (chính vi phạm đã gây ra nó) không bị đếm lại sau khi gỡ sớm — cần đủ 3 vi phạm mới, 2 thì chưa.
     */
    @Test
    void triggeringViolationIsNotRecountedAfterAnEarlyLift() {
        Instant startedAt = T0.minus(Duration.ofDays(5));
        priorLateCancel(startedAt);
        data.restriction(customer, startedAt, startedAt.plus(Duration.ofDays(30)), T0.minus(Duration.ofDays(4)),
                receptionist);

        ok(cancel(customerToken, booked(data.pet(customer), TODAY, LocalTime.of(20, 0)), null), "Đã hủy lịch hẹn");
        ok(cancel(customerToken, booked(data.pet(customer), TODAY, LocalTime.of(20, 30)), null), "Đã hủy lịch hẹn");

        assertThat(restrictionCount()).isEqualTo(1);
    }

    @Test
    void boardingViolationsCountTowardsTheThreshold() {
        doReturn(2).when(boarding).countViolations(anyLong(), any());

        ok(cancel(customerToken, booked(data.pet(customer), TODAY, LocalTime.of(20, 0)), null), "Đã hủy lịch hẹn");

        assertThat(restrictionCount()).isEqualTo(1);
    }

    @Test
    void failedRestrictionInsertRollsBackTheLateCancel() {
        failOn("booking_restrictions", "INSERT");
        priorLateCancel(T0.minus(Duration.ofDays(3)));
        priorLateCancel(T0.minus(Duration.ofDays(2)));
        long late = booked(data.pet(customer), TODAY, LocalTime.of(20, 0));

        ResponseEntity<Map<String, Object>> response = cancel(customerToken, late, null);

        assertThat(response.getStatusCode().is5xxServerError() || response.getStatusCode() == HttpStatus.CONFLICT)
                .as("%s", response.getBody()).isTrue();
        assertThat(row(late)).containsEntry("status", "BOOKED").containsEntry("late_cancel", false);
        assertThat(restrictionCount()).isZero();
    }

    @Test
    void evaluateAfterViolationRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> restrictionApi.evaluateAfterViolation(customer, T0))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ================================================================ validate, quyền

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "[]",
            "{\"slotDate\": \"2026-10-17\",",
            "{\"slotDate\": \"2026-13-01\", \"slotStart\": \"09:00\"}",
            "{\"slotDate\": \"2026-10-17\", \"slotStart\": \"25:00\"}",
            "{\"slotDate\": \"2026-10-17\", \"slotStart\": \"9:00\"}",
            "{\"slotDate\": \"2026-10-17\", \"slotStart\": \"09:00:00\"}",
            "{\"slotDate\": \"2026-10-17\", \"slotStart\": 900}"})
    void malformedRescheduleBodiesAre400AndWriteNothing(String json) {
        String xmin = xmin(appointment);

        assertError(raw(customerToken, "/api/appointments/" + appointment + "/reschedule", json,
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertThat(xmin(appointment)).isEqualTo(xmin);
    }

    @Test
    void validationErrorsAndMalformedPath() {
        ResponseEntity<Map<String, Object>> missing = raw(customerToken,
                "/api/appointments/" + appointment + "/reschedule", "{}", MediaType.APPLICATION_JSON);
        assertError(missing, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) missing.getBody().get("message")).contains("Thiếu ngày hẹn", "Thiếu giờ bắt đầu của khung");

        ResponseEntity<Map<String, Object>> tooLong = cancel(customerToken, appointment,
                "{\"reason\":\"" + "x".repeat(301) + "\"}");
        assertError(tooLong, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) tooLong.getBody().get("message")).contains("Lý do tối đa 300 ký tự");

        assertError(raw(customerToken, "/api/appointments/abc/cancel", "{}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Tham số 'appointmentId' sai kiểu dữ liệu");
        assertThat(row(appointment)).containsEntry("status", "BOOKED");
    }

    @Test
    void validationRunsBeforeTheRoleCheck() {
        assertError(raw(token(data.staff("VET", branch)), "/api/appointments/" + appointment + "/reschedule", "{}",
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN"})
    void otherRolesAre403(String role) {
        String token = token(data.staff(role, List.of("SUPER_MANAGER", "ADMIN").contains(role) ? null : branch));

        assertError(reschedule(token, appointment, DAY, "09:00"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertError(cancel(token, appointment, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertThat(row(appointment)).containsEntry("status", "BOOKED").containsEntry("reschedule_count", 0);
    }

    @Test
    void otherCustomersAppointmentAndMissingShareOne404() {
        long otherAccount = data.customerAccount();
        data.onlineCustomer(otherAccount, "Trần Thị Bình");
        String other = token(otherAccount);

        assertError(cancel(other, appointment, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy lịch hẹn #" + appointment);
        assertError(reschedule(other, appointment, DAY, "09:00"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy lịch hẹn #" + appointment);
        assertError(cancel(customerToken, 987654321L, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy lịch hẹn #987654321");
        assertThat(row(appointment)).containsEntry("status", "BOOKED");
    }

    @Test
    void receptionistOfAnotherBranchIs403() {
        String other = token(data.staff("RECEPTIONIST", otherBranch));

        assertError(cancel(other, appointment, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertError(reschedule(other, appointment, DAY, "09:00"), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertThat(row(appointment)).containsEntry("status", "BOOKED");
    }

    @Test
    void mustChangePasswordWinsOverABadBodyAndTheRole() {
        long vet = data.staff("VET", branch);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);

        assertError(raw(token(vet), "/api/appointments/" + appointment + "/reschedule", "[]",
                MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
    }

    @Test
    void wrongMediaTypeMethodAndNoToken() {
        assertThat(raw(customerToken, "/api/appointments/" + appointment + "/cancel", "reason=x",
                MediaType.TEXT_PLAIN).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        assertThat(http.exchange(URI.create(http.getRootUri() + "/api/appointments/" + appointment + "/cancel"),
                HttpMethod.GET, new HttpEntity<>(headers), MAP).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(cancel(null, appointment, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ================================================================ transaction

    @Test
    void failedUpdateWritesNothingAndReleasesAllLocks() {
        failOn("appointments", "UPDATE");
        String xmin = xmin(appointment);

        ResponseEntity<Map<String, Object>> response = reschedule(receptionistToken, appointment, DAY, "09:00");

        assertThat(response.getStatusCode().is5xxServerError() || response.getStatusCode() == HttpStatus.CONFLICT)
                .as("%s", response.getBody()).isTrue();
        assertThat(xmin(appointment)).isEqualTo(xmin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'", Long.class))
                .isZero();
        jdbc.execute("DROP TRIGGER it_fail_appointments ON appointments");
        ok(reschedule(receptionistToken, appointment, DAY, "09:00"), "Đã đổi khung giờ");
    }

    // ================================================================ khóa + race

    /** Đổi khung ∥ đặt lịch tranh chỗ cuối của khung — xếp hàng ở khóa {@code branches}. */
    @Test
    void lastSlotGoesToExactlyOneOfRescheduleAndBooking() throws Exception {
        long otherPet = data.pet(customer);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch},
                WAITING_ON_BRANCH, j -> {
                }, List.of(() -> reschedule(receptionistToken, appointment, DAY, "09:00"),
                        () -> book(receptionistToken, otherPet, DAY, "09:00")), 2);

        assertThat(results).extracting(r -> r.getStatusCode().value())
                .containsExactlyInAnyOrder(200, 400);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE branch_id = ? AND slot_date = ? AND slot_start = '09:00'
                  AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')""", Long.class, branch, Date.valueOf(DAY)))
                .isEqualTo(1);
    }

    /** Bấm đổi khung hai lần: lần sau chờ khóa thú, đọc lại dưới khóa thấy đã ở khung mới → 200 không ghi. */
    @Test
    void doubleRescheduleIncrementsOnce() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), WAITING_ON_PET,
                j -> {
                }, List.of(() -> reschedule(customerToken, appointment, DAY, "09:00"),
                        () -> reschedule(customerToken, appointment, DAY, "09:00")), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        assertThat(row(appointment)).containsEntry("reschedule_count", 1);
    }

    /** Hủy ∥ đổi khung cùng lịch: hủy lấy dòng trước, đổi khung đọc lại dưới khóa thấy CANCELLED → 409. */
    @Test
    void rescheduleQueuedBehindACancelIs409() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(rowLockSql(), new Object[] {appointment},
                WAITING_ON_ROW, j -> {
                }, List.of(() -> cancel(customerToken, appointment, null),
                        () -> reschedule(customerToken, appointment, DAY, "09:00")), 2);

        assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(results.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang BOOKED");
        assertThat(row(appointment)).containsEntry("status", "CANCELLED").containsEntry("reschedule_count", 0);
    }

    /** Hai lần hủy cùng lịch: lần sau đọc lại dưới khóa → 409, chỉ một vi phạm được ghi. */
    @Test
    void doubleLateCancelCountsOneViolation() throws Exception {
        long late = booked(data.pet(customer), TODAY, LocalTime.of(20, 0));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(rowLockSql(), new Object[] {late},
                WAITING_ON_ROW, j -> {
                }, List.of(() -> cancel(customerToken, late, null), () -> cancel(customerToken, late, null)), 2);

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE customer_id = ? AND late_cancel""", Long.class, customer))
                .isEqualTo(1);
    }

    /**
     * Hai lần hủy muộn đồng thời (hai lịch khác nhau) khi khách đã có 1 vi phạm: cả hai xếp hàng ở khóa 1002; lần sau
     * đếm thấy lần trước vừa commit (READ COMMITTED) → đúng một dòng hạn chế, không phải 0 hay 2.
     */
    @Test
    void concurrentThirdViolationCreatesExactlyOneRestriction() throws Exception {
        priorLateCancel(T0.minus(Duration.ofDays(3)));
        long first = booked(data.pet(customer), TODAY, LocalTime.of(20, 0));
        long second = booked(data.pet(customer), TODAY, LocalTime.of(20, 30));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(),
                new Object[] {1002, (int) (customer % Integer.MAX_VALUE)}, WAITING_ON_PET, j -> {
                }, List.of(() -> cancel(customerToken, first, null), () -> cancel(receptionistToken, second, null)), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        assertThat(restrictionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT violation_count FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customer)).isEqualTo(3);
    }

    /** Ngày nghỉ commit trong lúc đổi khung đang chờ khóa {@code branches} → đổi khung thấy nó (BR-LH-02). */
    @Test
    void holidayCommittedWhileWaitingRejectsReschedule() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch},
                WAITING_ON_BRANCH, j -> j.update(
                        "INSERT INTO holidays (branch_id, holiday_date, reason, created_by) VALUES (?, ?, 'IT', ?)",
                        branch, Date.valueOf(DAY.plusDays(1)), receptionist),
                List.of(() -> reschedule(receptionistToken, appointment, DAY.plusDays(1), "09:00")), 1);

        assertError(results.get(0), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION",
                "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)");
    }

    /** Request không có quyền trả ngay, không xếp hàng ở khóa nào (phân quyền trước khóa). */
    @Test
    void unauthorizedRequestsTakeNoLock() throws Exception {
        String other = token(data.staff("RECEPTIONIST", otherBranch));

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch}, null,
                j -> {
                }, List.of(() -> reschedule(other, appointment, DAY, "09:00")), 0);

        assertError(results.get(0), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
    }

    // ================================================================ helpers

    private long booked(long petId, LocalDate date, LocalTime start) {
        return data.appointment(customer, petId, branch, medical, "MEDICAL", date, start, "BOOKED", receptionist);
    }

    private void priorLateCancel(Instant cancelledAt) {
        long id = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL",
                LocalDate.ofInstant(cancelledAt, TimeConfig.BUSINESS_ZONE).plusDays(1),
                LocalTime.of(8, 0), "CANCELLED", receptionist);
        jdbc.update("""
                UPDATE appointments SET late_cancel = true, cancel_source = 'CUSTOMER', cancelled_at = ? WHERE id = ?""",
                Timestamp.from(cancelledAt), id);
    }

    private void priorNoShow(LocalDate date) {
        data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", date, LocalTime.of(8, 0), "NO_SHOW",
                receptionist);
    }

    private long restrictionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                customer);
    }

    private void failOn(String table, String operation) {
        jdbc.execute("CREATE FUNCTION it_fail_" + table + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'it: " + operation + " " + table + " thất bại'; END $$");
        jdbc.execute("CREATE TRIGGER it_fail_" + table + " BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION it_fail_" + table + "()");
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", id);
    }

    private String xmin(long id) {
        return jdbc.queryForObject("SELECT xmin::text FROM appointments WHERE id = ?", String.class, id);
    }

    private void assertRuleWritesNothing(Callable<ResponseEntity<Map<String, Object>>> call, String message) {
        String xmin = xmin(appointment);
        try {
            assertError(call.call(), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(xmin(appointment)).isEqualTo(xmin);
        assertThat(restrictionCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> slots(LocalDate date) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(receptionistToken);
        ResponseEntity<Map<String, Object>> response = http.exchange(URI.create(http.getRootUri() + "/api/branches/"
                + branch + "/available-slots?serviceId=" + medical + "&date=" + date), HttpMethod.GET,
                new HttpEntity<>(headers), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static int booked(List<Map<String, Object>> slots, String start) {
        return slots.stream().filter(s -> start.equals(s.get("slotStart"))).findFirst()
                .map(s -> ((Number) s.get("booked")).intValue()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Long> listIds(String token, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map<String, Object>> response = http.exchange(
                URI.create(http.getRootUri() + "/api/appointments?" + query), HttpMethod.GET,
                new HttpEntity<>(headers), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> page = (Map<String, Object>) response.getBody().get("data");
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(r -> ((Number) r.get("appointmentId")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(String token, long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map<String, Object>> response = http.exchange(
                URI.create(http.getRootUri() + "/api/appointments/" + id), HttpMethod.GET, new HttpEntity<>(headers),
                MAP);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody().get("data");
    }

    private ResponseEntity<Map<String, Object>> reschedule(String token, long id, LocalDate date, String start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        return post(token, id, "reschedule", body);
    }

    private ResponseEntity<Map<String, Object>> cancel(String token, long id, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments/" + id + "/cancel"), HttpMethod.POST,
                new HttpEntity<>(json, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> book(String token, long petId, LocalDate date, String start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String token, long id, String action, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments/" + id + "/" + action),
                HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String token, String path, String body, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST, new HttpEntity<>(body, headers),
                MAP);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "RescheduleCancelIT")).accessToken();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response, String message) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code")
                .containsEntry("message", message).containsEntry("code", 200);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsOnlyKeys(APPOINTMENT_KEYS);
        return data;
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

    /**
     * Một luồng giữ khóa ({@code lockSql}) trong transaction riêng; các request bắn lần lượt, mỗi request phải đứng chờ
     * khóa ({@code pg_stat_activity}, {@code waitingLike}) trước request kế tiếp, nên thứ tự nhả = thứ tự danh sách. Rồi
     * luồng giữ khóa chạy {@code beforeRelease} và commit. {@code expectedWaiting = 0}: request không được chờ. Khuôn
     * {@code BookAppointmentIT.whileHeld}.
     */
    private List<ResponseEntity<Map<String, Object>>> whileHeld(String lockSql, Object[] lockArgs, String waitingLike,
            Consumer<JdbcTemplate> beforeRelease, List<Callable<ResponseEntity<Map<String, Object>>>> requests,
            int expectedWaiting) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList(lockSql, lockArgs);
                held.countDown();
                await(release);
                beforeRelease.accept(jdbc);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                Future<ResponseEntity<Map<String, Object>>> call = pool.submit(request);
                calls.add(call);
                if (expectedWaiting == 0) {
                    results.add(call.get(10, TimeUnit.SECONDS));
                } else {
                    awaitWaiting(waitingLike, calls.size());
                }
            }
            if (expectedWaiting > 0) {
                for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                    assertThat(call.isDone()).as("request phải chờ khóa").isFalse();
                }
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            if (expectedWaiting > 0) {
                for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                    results.add(call.get(30, TimeUnit.SECONDS));
                }
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
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE ?""", Long.class, like);
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
                throw new IllegalStateException("hết giờ chờ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String branchLockSql() {
        return "SELECT id FROM branches WHERE id = ? FOR UPDATE";
    }

    private static String rowLockSql() {
        return "SELECT id FROM appointments WHERE id = ? FOR UPDATE";
    }

    private static String petLockSql() {
        return "SELECT pg_advisory_xact_lock(?, ?)::text";
    }

    private static Object[] petLockArgs(long petId) {
        return new Object[] {1001, (int) (petId % Integer.MAX_VALUE)};
    }
}
