package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.data.jpa.repository.Query;
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
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * T16 phần 2 — đặt lịch {@code POST /api/appointments} (appointment-v1 #2, UC39; docs/adr/0045, 0047) qua HTTP trên
 * Postgres 17 thật. {@code BranchQueryService}, {@code CatalogQueryService} thật; {@code PetQueryApi} /
 * {@code CustomerQueryApi} (BE-2, placeholder) được thay bằng {@link JdbcPetCustomerModule} đọc SQL mỗi lần gọi.
 * <p>
 * Đồng hồ {@code T0} = 2026-10-15 09:00 giờ Việt Nam. Ba chi nhánh (ACTIVE, ACTIVE, DRAFT) mở 7 ngày 08:00–12:00 →
 * khung 08:00 … 11:30. {@code DAY} = 2026-10-17, trong cửa sổ của khách. Đặt lịch thành công ở happy path cũng là bằng
 * chứng các service {@code readOnly} của branch / catalog tham gia transaction ghi mà không đặt cờ readOnly — nếu có,
 * {@code AppointmentCodeGenerator.nextCode} đã ném lỗi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BookAppointmentIT.TestBeans.class})
class BookAppointmentIT {

    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final String START = "08:30";
    private static final List<String> APPOINTMENT_KEYS = List.of("appointmentId", "code", "customerId", "customerName",
            "petId", "petName", "branchId", "branchName", "serviceId", "serviceName", "serviceGroup", "slotDate",
            "slotStart", "status", "channel", "rescheduleCount", "lateCancel", "cancelSource", "cancelReason",
            "cancelledAt", "note", "visitId", "canReschedule", "canCancel");
    private static final String WAITING_ON_BRANCH = "%from branches%for no key update%";
    private static final String WAITING_ON_PET = "%pg_advisory_xact_lock%";
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

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long draft;
    private long medical;
    private long grooming;
    private long receptionist;
    private long customerAccount;
    private long customer;
    private long pet;
    private String receptionistToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        draft = data.branch("DRAFT");
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch, draft)) {
            data.openingHours(b, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
            data.branchService(b, medical, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        customerAccount = data.customerAccount();
        customer = data.onlineCustomer(customerAccount, "Nguyễn Văn An");
        pet = data.pet(customer);
        receptionistToken = token(receptionist);
        customerToken = token(customerAccount);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_appointment_insert ON appointments");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_appointment_insert()");
        data.cleanUp();
    }

    // ================================================================ thành công

    @Test
    void customerBooksOnlineAndResponseMatchesTheRowColumnByColumn() {
        ResponseEntity<Map<String, Object>> response = post(customerToken,
                body(pet, branch, medical, DAY, START, "  Bé hay sợ tiếng ồn  "));

        Map<String, Object> appointment = created(response);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?",
                ((Number) appointment.get("appointmentId")).longValue());
        assertThat((String) appointment.get("code")).matches("^LH-261015-\\d{4,}$").isEqualTo(row.get("code"));
        assertThat(appointment)
                .containsEntry("customerId", (int) customer).containsEntry("customerName", "Nguyễn Văn An")
                .containsEntry("petId", (int) pet).containsEntry("petName", "Milu")
                .containsEntry("branchId", (int) branch)
                .containsEntry("serviceId", (int) medical).containsEntry("serviceGroup", "MEDICAL")
                .containsEntry("slotDate", "2026-10-17").containsEntry("slotStart", "08:30")
                .containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                .containsEntry("rescheduleCount", 0).containsEntry("lateCancel", false)
                .containsEntry("cancelSource", null).containsEntry("cancelReason", null)
                .containsEntry("cancelledAt", null).containsEntry("note", "Bé hay sợ tiếng ồn")
                .containsEntry("visitId", null).containsEntry("canReschedule", true).containsEntry("canCancel", true);
        assertThat((String) appointment.get("branchName")).startsWith("AppointmentIT ");
        assertThat((String) appointment.get("serviceName")).startsWith("Dịch vụ ");
        assertThat(row).containsEntry("customer_id", customer).containsEntry("pet_id", pet)
                .containsEntry("branch_id", branch).containsEntry("service_id", medical)
                .containsEntry("service_group", "MEDICAL").containsEntry("slot_date", Date.valueOf(DAY))
                .containsEntry("status", "BOOKED").containsEntry("channel", "ONLINE")
                .containsEntry("booked_by", customerAccount).containsEntry("reschedule_count", 0)
                .containsEntry("late_cancel", false).containsEntry("cancel_source", null)
                .containsEntry("reminded_at", null).containsEntry("note", "Bé hay sợ tiếng ồn");
        assertThat(row.get("slot_start").toString()).isEqualTo("08:30:00");
        assertThat(response.getBody()).containsEntry("message", "Đã đặt lịch hẹn").containsEntry("code", 201);
    }

    @Test
    void receptionistBooksAtCounterForThePetsCurrentOwner() {
        long owner = data.counterCustomer();
        long counterPet = data.pet(owner);

        Map<String, Object> appointment = created(post(receptionistToken,
                body(counterPet, branch, grooming, TODAY, "09:30", "   ")));

        assertThat(appointment).containsEntry("customerId", (int) owner).containsEntry("channel", "COUNTER")
                .containsEntry("serviceGroup", "GROOMING").containsEntry("note", null);
        assertThat((String) appointment.get("customerName")).startsWith("Khách ");
        assertThat(jdbc.queryForMap("SELECT customer_id, booked_by, channel FROM appointments WHERE pet_id = ?",
                counterPet)).containsEntry("customer_id", owner).containsEntry("booked_by", receptionist)
                .containsEntry("channel", "COUNTER");
    }

    @Test
    void receptionistMayBookForAPetSeenAtAnotherBranchAndForARestrictedOrPendingCustomer() {
        data.restriction(customer, T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)), null, null);
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);
        data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY.plusDays(3), LocalTime.of(8, 0),
                "COMPLETED", receptionist);

        assertThat(created(post(receptionistToken, body(pet, branch, medical, DAY, START, null))))
                .containsEntry("customerId", (int) customer).containsEntry("channel", "COUNTER");
    }

    @Test
    void unknownFieldsAreIgnoredSoTheCustomerCannotPickAnotherOwnerOrChannel() {
        long stranger = data.counterCustomer();
        Map<String, Object> body = body(pet, branch, medical, DAY, START, null);
        body.put("customerId", stranger);
        body.put("channel", "COUNTER");
        body.put("status", "COMPLETED");

        assertThat(created(post(customerToken, body))).containsEntry("customerId", (int) customer)
                .containsEntry("channel", "ONLINE").containsEntry("status", "BOOKED");
    }

    @Test
    void numericStringIdIsCoercedLikeElsewhere() {
        Map<String, Object> body = body(pet, branch, medical, DAY, START, null);
        body.put("petId", String.valueOf(pet));

        assertThat(created(post(customerToken, body))).containsEntry("petId", (int) pet);
    }

    // ================================================================ phân quyền

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "VET", "CARETAKER"})
    void otherRolesAreForbiddenAndWriteNothing(String role) {
        long staff = data.staff(role, role.equals("ADMIN") || role.equals("SUPER_MANAGER") ? null : branch);

        assertError(post(token(staff), body(pet, branch, medical, DAY, START, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
        assertThat(count()).isZero();
    }

    @Test
    void unauthenticatedIs401() {
        assertError(post(null, body(pet, branch, medical, DAY, START, null)), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", null);
        assertError(post("garbage.token.value", body(pet, branch, medical, DAY, START, null)),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
    }

    @Test
    void roleChangedInTheDatabaseAppliesToTheSameToken() {
        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", receptionist);

        assertError(post(receptionistToken, body(pet, branch, medical, DAY, START, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", null);
    }

    @Test
    void mustChangePasswordBlocksBooking() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);

        ResponseEntity<Map<String, Object>> response = post(receptionistToken,
                body(pet, branch, medical, DAY, START, null));

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    @Test
    void receptionistOfAnotherBranchIsDeniedWithoutWaitingOnAnyLock() throws Exception {
        long other = data.staff("RECEPTIONIST", otherBranch);
        String otherToken = token(other);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), null, j -> {
        }, List.of(() -> post(otherToken, body(pet, branch, medical, DAY, START, null))), 0);

        assertError(results.get(0), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertThat(count()).isZero();
    }

    @Test
    void forbiddenScopeBeatsMissingPet() {
        long other = data.staff("RECEPTIONIST", otherBranch);

        assertError(post(token(other), body(999_999_999L, branch, medical, DAY, START, null)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
    }

    // ================================================================ 404

    @Test
    void missingPetAndSomeoneElsesPetAreTheSameNotFound() {
        long strangerPet = data.pet(data.counterCustomer());

        Map<String, Object> missing = withoutVolatile(post(customerToken,
                body(999_999_999L, branch, medical, DAY, START, null)).getBody());
        ResponseEntity<Map<String, Object>> foreign = post(customerToken,
                body(strangerPet, branch, medical, DAY, START, null));

        assertError(foreign, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy thú cưng #" + strangerPet);
        assertThat(missing).containsEntry("message", "Không tìm thấy thú cưng #999999999")
                .containsEntry("errorCode", "RESOURCE_NOT_FOUND");
        assertThat(count()).isZero();
    }

    @Test
    void notFoundBeatsEveryRule() {
        long strangerPet = data.pet(data.counterCustomer());
        data.restriction(customer, T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)), null, null);
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);

        assertError(post(customerToken, body(strangerPet, branch, medical, TODAY.minusDays(1), "08:10", null)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy thú cưng #" + strangerPet);
        assertError(post(customerToken, body(pet, 999_999_999L, medical, TODAY.minusDays(1), "08:10", null)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy chi nhánh #999999999");
    }

    @Test
    void missingServiceAndBranchAreNotFound() {
        assertError(post(customerToken, body(pet, branch, 999_999_999L, DAY, START, null)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "Không tìm thấy dịch vụ #999999999");
        assertError(post(customerToken, body(pet, 999_999_999L, medical, DAY, START, null)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "Không tìm thấy chi nhánh #999999999");
    }

    @Test
    void draftBranchIsNotFoundForTheCustomerButRuleLhZeroOneForItsReceptionist() {
        assertError(post(customerToken, body(pet, draft, medical, DAY, START, null)), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "Không tìm thấy chi nhánh #" + draft);

        long draftReceptionist = data.staff("RECEPTIONIST", draft);
        assertRule(post(token(draftReceptionist), body(pet, draft, medical, DAY, START, null)),
                "Chi nhánh chưa nhận đặt lịch (BR-LH-01)");
    }

    // ================================================================ guard

    @Test
    void unbookableServiceIsRuleLhZeroOne() {
        String message = "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)";
        long boarding = data.service("BOARDING");
        data.branchService(branch, boarding, true);
        long inactive = data.service("GROOMING");
        data.branchService(branch, inactive, true);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", inactive);
        long disabled = data.service("GROOMING");
        data.branchService(branch, disabled, false);
        long neverConfigured = data.service("GROOMING");

        for (long service : List.of(boarding, inactive, disabled, neverConfigured)) {
            assertRule(post(customerToken, body(pet, branch, service, DAY, START, null)), message);
        }
    }

    @Test
    void pendingLinkDecisionBlocksTheCustomer() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);

        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)),
                "Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám (BR-TK-19)");
    }

    @Test
    void ruleLhZeroOneBeatsPendingLinkDecision() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                medical);

        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)),
                "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)");
    }

    @Test
    void activeRestrictionBlocksTheCustomerOnlyWhileItIsInForce() {
        String message = "Bạn đang bị hạn chế đặt lịch online đến 09:00 14/11/2026, vui lòng liên hệ chi nhánh để "
                + "đặt lịch (BR-LH-09)";
        data.restriction(customer, T0, T0.plus(Duration.ofDays(30)), null, null);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        jdbc.update("UPDATE booking_restrictions SET lifted_at = ?, lifted_by = ? WHERE customer_id = ?",
                java.sql.Timestamp.from(T0), receptionist, customer);
        data.restriction(customer, T0.minus(Duration.ofDays(31)), T0, null, null);
        data.restriction(customer, T0.plusSeconds(1), T0.plus(Duration.ofDays(30)), null, null);

        assertThat(created(post(customerToken, body(pet, branch, medical, DAY, START, null))))
                .as("đã gỡ, hết hạn đúng lúc này, chưa bắt đầu: đều không còn hạn chế").containsEntry("status", "BOOKED");
    }

    @Test
    void deceasedPetCannotBeBookedByAnyone() {
        jdbc.update("UPDATE pets SET deceased_on = ? WHERE id = ?", Date.valueOf(TODAY), pet);

        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)),
                "Thú cưng đã mất, không đặt được lịch (BR-KH-05)");
        assertRule(post(receptionistToken, body(pet, branch, medical, DAY, START, null)),
                "Thú cưng đã mất, không đặt được lịch (BR-KH-05)");
    }

    @Test
    void customerWindowIsTwentyFourHoursToThirtyDaysInclusive() {
        String message = "Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)";
        LocalDate tomorrow = TODAY.plusDays(1);
        assertRule(post(customerToken, body(pet, branch, medical, tomorrow, "08:30", null)), message);
        assertRule(post(customerToken, body(pet, branch, medical, TODAY.plusDays(31), "08:00", null)), message);
        assertRule(post(customerToken, body(pet, branch, medical, TODAY.minusDays(1), "09:00", null)), message);

        created(post(customerToken, body(pet, branch, medical, tomorrow, "09:00", null)));
        long secondPet = data.pet(customer);
        created(post(customerToken, body(secondPet, branch, medical, TODAY.plusDays(30), "11:30", null)));
    }

    @Test
    void receptionistMayBookAnySlotNotYetStarted() {
        assertRule(post(receptionistToken, body(pet, branch, medical, TODAY, "09:00", null)),
                "Khung giờ đã bắt đầu (BR-LH-04)");
        assertRule(post(receptionistToken, body(pet, branch, medical, TODAY, "08:30", null)),
                "Khung giờ đã bắt đầu (BR-LH-04)");

        created(post(receptionistToken, body(pet, branch, medical, TODAY, "09:30", null)));
        long secondPet = data.pet(customer);
        created(post(receptionistToken, body(secondPet, branch, medical, TODAY.plusDays(90), "08:00", null)));
    }

    @Test
    void windowIsCheckedBeforeTheSlotGrid() {
        assertRule(post(receptionistToken, body(pet, branch, medical, TODAY.minusDays(1), "08:10", null)),
                "Khung giờ đã bắt đầu (BR-LH-04)");
    }

    @Test
    void slotOffTheGridOutsideHoursOrOnAHolidayIsRuleLhZeroTwo() {
        String message = "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút (BR-LH-02)";
        assertRule(post(customerToken, body(pet, branch, medical, DAY, "08:10", null)), message);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, "12:00", null)), message);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, "07:30", null)), message);
        data.holiday(branch, DAY, receptionist);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        created(post(customerToken, body(pet, branch, medical, DAY.plusDays(1), "11:30", null)));
    }

    @Test
    void twoBookedAppointmentsAnywhereAreTheLimit() {
        data.appointment(customer, pet, otherBranch, medical, "MEDICAL", DAY.plusDays(5), LocalTime.of(8, 0), "BOOKED",
                receptionist);
        data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY.plusDays(6), LocalTime.of(8, 0),
                "BOOKED", receptionist);

        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)),
                "Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)");
        assertRule(post(receptionistToken, body(pet, branch, medical, DAY, START, null)),
                "Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)");
    }

    @Test
    void checkedInCompletedCancelledAndNoShowAreNotCountedTowardsTheTwoBookedLimit() {
        for (String status : List.of("CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW")) {
            data.appointment(customer, pet, otherBranch, medical, "MEDICAL", DAY.plusDays(10), LocalTime.of(8, 0),
                    status, receptionist);
        }
        data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY.plusDays(6), LocalTime.of(8, 0),
                "BOOKED", receptionist);

        created(post(customerToken, body(pet, branch, medical, DAY, START, null)));
    }

    @Test
    void sameSlotCountsBookedCheckedInAndCompletedOfAnyGroupAtAnyBranch() {
        String message = "Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)";
        long appointment = data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY,
                LocalTime.of(8, 30), "CHECKED_IN", receptionist);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?", appointment);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        jdbc.update("UPDATE appointments SET status = 'NO_SHOW' WHERE id = ?", appointment);
        created(post(customerToken, body(pet, branch, medical, DAY, START, null)));
    }

    @Test
    void sameGroupSameDayCountsActiveStatusesAndNamesTheGroup() {
        long appointment = data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY,
                LocalTime.of(11, 0), "COMPLETED", receptionist);
        assertRule(post(customerToken, body(pet, branch, grooming, DAY, START, null)),
                "Thú cưng đã có lịch Thẩm mỹ trong ngày này (BR-LH-05)");

        created(post(customerToken, body(pet, branch, medical, DAY, START, null)));
        assertRule(post(receptionistToken, body(pet, branch, medical, DAY, "10:00", null)),
                "Thú cưng đã có lịch Khám/Tiêm trong ngày này (BR-LH-05)");

        jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_source = 'CUSTOMER' WHERE id = ?",
                appointment);
        created(post(receptionistToken, body(pet, branch, grooming, DAY, "10:00", null)));
    }

    @Test
    void fullSlotIsRuleLhZeroThreeAndOnlyActiveAppointmentsOccupyIt() {
        String message = "Khung giờ đã hết chỗ (BR-LH-03)";
        long otherOwner = data.counterCustomer();
        long otherPet = data.pet(otherOwner);
        long occupant = data.appointment(otherOwner, otherPet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 30),
                "CHECKED_IN", receptionist);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        data.quotaDefault(branch, "MEDICAL", 2, receptionist);
        data.slotQuota(branch, "MEDICAL", DAY, LocalTime.of(8, 30), 0, receptionist);
        jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_source = 'STAFF' WHERE id = ?", occupant);
        assertRule(post(customerToken, body(pet, branch, medical, DAY, START, null)), message);

        created(post(customerToken, body(pet, branch, grooming, DAY, START, null)));
        created(post(customerToken, body(data.pet(customer), branch, medical, DAY, "09:00", null)));
    }

    // ================================================================ validate

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "[]",
            "{\"petId\": 1,",
            "{\"petId\": 1.5, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": \"08:30\"}",
            "{\"petId\": \"abc\", \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": \"08:30\"}",
            "{\"petId\": 1, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-13-01\", \"slotStart\": \"08:30\"}",
            "{\"petId\": 1, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": \"25:00\"}",
            "{\"petId\": 1, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": \"8:30\"}",
            "{\"petId\": 1, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": \"08:30:00\"}",
            "{\"petId\": 1, \"branchId\": 1, \"serviceId\": 1, \"slotDate\": \"2026-10-17\", \"slotStart\": 830}"})
    void malformedBodiesAreRejectedBeforeAnyRead(String json) {
        assertError(postRaw(customerToken, json, MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertThat(count()).isZero();
    }

    @Test
    void missingAndNonPositiveFieldsAreValidationErrors() {
        ResponseEntity<Map<String, Object>> missing = postRaw(customerToken, "{}", MediaType.APPLICATION_JSON);
        assertError(missing, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) missing.getBody().get("message")).contains("Thiếu thú cưng", "Thiếu chi nhánh",
                "Thiếu dịch vụ", "Thiếu ngày hẹn", "Thiếu giờ bắt đầu của khung");

        Map<String, Object> zero = body(0L, branch, medical, DAY, START, null);
        ResponseEntity<Map<String, Object>> rejected = post(customerToken, zero);
        assertError(rejected, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) rejected.getBody().get("message")).contains("Mã thú cưng không hợp lệ");

        assertError(post(customerToken, body(pet, branch, medical, DAY, START, "x".repeat(501))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat(count()).isZero();
    }

    @Test
    void validationRunsBeforeTheRoleCheck() {
        assertError(postRaw(token(data.staff("VET", branch)), "{}", MediaType.APPLICATION_JSON),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
    }

    @Test
    void wrongMediaTypeAndMethod() {
        assertThat(postRaw(customerToken, "petId=1", MediaType.TEXT_PLAIN).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        // GET /api/appointments là danh sách (#3, docs/adr/0048); PUT không có handler.
        assertThat(http.exchange(URI.create(http.getRootUri() + "/api/appointments"), HttpMethod.PUT,
                new HttpEntity<>(headers), MAP).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // ================================================================ transaction

    @Test
    void failedInsertWritesNothingAndReleasesBothLocks() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION it_fail_appointment_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'it: insert appointments thất bại'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER it_fail_appointment_insert BEFORE INSERT ON appointments
                FOR EACH ROW EXECUTE FUNCTION it_fail_appointment_insert()""");

        ResponseEntity<Map<String, Object>> failed = post(customerToken, body(pet, branch, medical, DAY, START, null));

        assertThat(failed.getStatusCode().is5xxServerError() || failed.getStatusCode() == HttpStatus.CONFLICT)
                .as("%s", failed.getBody()).isTrue();
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'", Long.class))
                .isZero();
        jdbc.execute("DROP TRIGGER it_fail_appointment_insert ON appointments");
        assertThat(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(1001, ?)", Boolean.class, (int) pet))
                .isTrue();
        created(post(customerToken, body(pet, branch, medical, DAY, START, null)));
    }

    @Test
    void doubleSubmitCreatesOneAppointmentAndTheSecondSeesItUnderThePetLock() throws Exception {
        Map<String, Object> body = body(pet, branch, medical, DAY, START, null);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), WAITING_ON_PET,
                j -> {
                }, List.of(() -> post(customerToken, body), () -> post(customerToken, body)), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST).findFirst().orElseThrow()
                .getBody()).containsEntry("message", "Thú cưng đã có lịch hẹn khác trùng khung giờ này (BR-LH-05)");
        assertThat(count()).isEqualTo(1);
    }

    // ================================================================ khóa + race (docs/adr/0045, 0047)

    /** I1: hai thú khác nhau tranh chỗ cuối của cùng khung — xếp hàng ở khóa {@code branches}. */
    @Test
    void lastSlotGoesToExactlyOneOfTwoConcurrentBookings() throws Exception {
        long otherPet = data.pet(customer);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch},
                WAITING_ON_BRANCH, j -> {
                }, List.of(() -> post(customerToken, body(pet, branch, medical, DAY, START, null)),
                        () -> post(receptionistToken, body(otherPet, branch, medical, DAY, START, null))), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST).findFirst().orElseThrow()
                .getBody()).containsEntry("message", "Khung giờ đã hết chỗ (BR-LH-03)");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM appointments WHERE branch_id = ? AND slot_date = ? AND slot_start = '08:30'
                  AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')""", Long.class, branch, Date.valueOf(DAY)))
                .isEqualTo(1);
    }

    /** I2: cùng một thú, hai chi nhánh — khóa thú tuần tự hóa phép đếm BR-LH-05 trên mọi chi nhánh. */
    @Test
    void secondBookedLimitHoldsAcrossBranchesUnderConcurrency() throws Exception {
        data.appointment(customer, pet, otherBranch, grooming, "GROOMING", DAY.plusDays(6), LocalTime.of(8, 0),
                "BOOKED", receptionist);
        long otherReceptionist = data.staff("RECEPTIONIST", otherBranch);
        String otherToken = token(otherReceptionist);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), WAITING_ON_PET,
                j -> {
                }, List.of(() -> post(customerToken, body(pet, branch, medical, DAY, START, null)),
                        () -> post(otherToken, body(pet, otherBranch, medical, DAY.plusDays(1), START, null))), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST).findFirst().orElseThrow()
                .getBody()).containsEntry("message", "Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Long.class, pet)).isEqualTo(2);
    }

    /** I3: cùng thú, cùng ngày, cùng nhóm, khác khung, khác chi nhánh. */
    @Test
    void sameGroupSameDayHoldsAcrossBranchesUnderConcurrency() throws Exception {
        long otherReceptionist = data.staff("RECEPTIONIST", otherBranch);
        String otherToken = token(otherReceptionist);

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), WAITING_ON_PET,
                j -> {
                }, List.of(() -> post(receptionistToken, body(pet, branch, medical, DAY, "08:00", null)),
                        () -> post(otherToken, body(pet, otherBranch, medical, DAY, "10:00", null))), 2);

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        assertThat(count()).isEqualTo(1);
    }

    /** I4: lịch chi nhánh (ngày nghỉ, quota, bật / tắt dịch vụ) đọc sau khóa {@code branches} (R2, R3 của docs/adr/0045). */
    @ParameterizedTest
    @ValueSource(strings = {"holiday", "quota", "service"})
    void branchScheduleChangedWhileWaitingOnTheBranchLockIsSeen(String change) throws Exception {
        Consumer<JdbcTemplate> commitWhileHeld = switch (change) {
            case "holiday" -> j -> j.update(
                    "INSERT INTO holidays (branch_id, holiday_date, reason, created_by) VALUES (?, ?, 'IT', ?)",
                    branch, Date.valueOf(DAY), receptionist);
            case "quota" -> j -> j.update("""
                    INSERT INTO slot_quotas (branch_id, service_group, slot_date, slot_start, quota, updated_by)
                    VALUES (?, 'MEDICAL', ?, '08:30', 0, ?)""", branch, Date.valueOf(DAY), receptionist);
            default -> j -> j.update(
                    "UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                    medical);
        };
        String expected = switch (change) {
            case "holiday" -> "Khung giờ không hợp lệ: ngoài giờ mở cửa, ngày nghỉ hoặc không đúng mốc 30 phút "
                    + "(BR-LH-02)";
            case "quota" -> "Khung giờ đã hết chỗ (BR-LH-03)";
            default -> "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)";
        };

        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch},
                WAITING_ON_BRANCH, commitWhileHeld,
                List.of(() -> post(customerToken, body(pet, branch, medical, DAY, START, null))), 1);

        assertRule(results.get(0), expected);
    }

    /** I5: {@code now} của BR-LH-04 lấy sau khi đã khóa — chờ khóa lâu làm khung rơi khỏi cửa sổ 24 giờ. */
    @Test
    void windowUsesTheTimeAfterTheLocksNotTheTimeOfArrival() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(branchLockSql(), new Object[] {branch},
                WAITING_ON_BRANCH, j -> clock.set(T0.plus(Duration.ofHours(1))),
                List.of(() -> post(customerToken, body(pet, branch, medical, TODAY.plusDays(1), "09:30", null))), 1);

        assertRule(results.get(0),
                "Chỉ đặt được khung bắt đầu sau ít nhất 24 giờ và trong vòng 30 ngày tới (BR-LH-04)");
    }

    /** I6: thú đọc sau khóa thú (docs/adr/0047 Q1) — luồng giữ khóa 1001 đánh dấu thú mất rồi commit. */
    @Test
    void petMarkedDeceasedWhileWaitingOnThePetLockIsSeen() throws Exception {
        List<ResponseEntity<Map<String, Object>>> results = whileHeld(petLockSql(), petLockArgs(pet), WAITING_ON_PET,
                j -> j.update("UPDATE pets SET deceased_on = ? WHERE id = ?", Date.valueOf(TODAY), pet),
                List.of(() -> post(customerToken, body(pet, branch, medical, DAY, START, null))), 1);

        assertRule(results.get(0), "Thú cưng đã mất, không đặt được lịch (BR-KH-05)");
    }

    /** I7: mã từ sequence không trùng dưới tải song song. */
    @Test
    void concurrentBookingsGetDistinctCodes() throws Exception {
        data.quotaDefault(branch, "MEDICAL", 20, receptionist);
        List<Callable<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long eachPet = data.pet(customer);
            calls.add(() -> post(receptionistToken, body(eachPet, branch, medical, DAY, START, null)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            Set<String> codes = new HashSet<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : pool.invokeAll(calls)) {
                codes.add((String) created(future.get(30, TimeUnit.SECONDS)).get("code"));
            }
            assertThat(codes).hasSize(8);
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================ index

    /**
     * Trên dữ liệu gần thật (2 000 thú × 10 lịch, 20 000 dòng, {@code ANALYZE}) chèn trong một transaction rollback, planner
     * <b>không</b> bị ép tắt seq scan: ba câu theo thú dùng index có {@code pet_id} đứng đầu
     * ({@code ix_appointments_pet_status} hoặc partial unique {@code uq_appointments_pet_slot_booked}), câu quota dùng
     * {@code ix_appointments_quota}. Bảng gần rỗng thì chọn index nào cũng như nhau, nên không assert trên dữ liệu rỗng.
     */
    @Test
    void countQueriesUseTheirIndexesOnRealisticData() {
        Map<String, String> plans = tx.execute(status -> {
            status.setRollbackOnly();
            jdbc.update("""
                    INSERT INTO customers (full_name, phone, created_channel)
                    SELECT 'EXPLAIN-IT ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 2000) g""");
            jdbc.update("""
                    INSERT INTO pets (customer_id, name, species, sex)
                    SELECT id, 'EXPLAIN-IT', 'DOG', 'MALE' FROM customers WHERE full_name LIKE 'EXPLAIN-IT %'""");
            jdbc.update("""
                    INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, slot_date,
                            slot_start, status, channel, booked_by)
                    SELECT 'LH-EX-' || g, p.customer_id, p.id, ?, ?, 'MEDICAL', DATE '2026-01-01' + (g / 2000),
                           TIME '08:00' + (g % 8) * INTERVAL '30 minutes',
                           (ARRAY['BOOKED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'])[1 + g % 4], 'COUNTER', ?
                    FROM generate_series(0, 19999) g
                    JOIN (SELECT id, customer_id, row_number() OVER (ORDER BY id) - 1 AS n FROM pets
                          WHERE name = 'EXPLAIN-IT') p ON p.n = g % 2000""", branch, medical, receptionist);
            jdbc.execute("ANALYZE appointments");
            long probe = jdbc.queryForObject("SELECT min(id) FROM pets WHERE name = 'EXPLAIN-IT'", Long.class);
            LocalDate date = LocalDate.of(2026, 1, 1);
            Map<String, String> result = new HashMap<>();
            result.put("countBookedByPet", plan("countBookedByPet", new Class<?>[] {Long.class}, probe));
            result.put("existsActiveSameSlot", plan("existsActiveSameSlot",
                    new Class<?>[] {Long.class, LocalDate.class, LocalTime.class}, probe, date, LocalTime.of(8, 0)));
            result.put("existsActiveSameGroupDay", plan("existsActiveSameGroupDay",
                    new Class<?>[] {Long.class, String.class, LocalDate.class}, probe, "MEDICAL", date));
            result.put("countActiveInSlot", plan("countActiveInSlot",
                    new Class<?>[] {Long.class, String.class, LocalDate.class, LocalTime.class}, branch, "MEDICAL",
                    date, LocalTime.of(8, 0)));
            return result;
        });

        for (String method : List.of("countBookedByPet", "existsActiveSameSlot", "existsActiveSameGroupDay")) {
            assertThat(plans.get(method)).as(method).doesNotContain("Seq Scan")
                    .containsAnyOf("ix_appointments_pet_status", "uq_appointments_pet_slot_booked");
        }
        assertThat(plans.get("countActiveInSlot")).doesNotContain("Seq Scan").contains("ix_appointments_quota");
    }

    @Test
    void restrictionQueryCanUseItsIndex() throws Exception {
        String sql = BookingRestrictionRepository.class.getMethod("existsActive", Long.class, Instant.class)
                .getAnnotation(Query.class).value().replace(":customerId", "?").replace(":at", "?");
        java.sql.Timestamp at = java.sql.Timestamp.from(T0);

        String plan = tx.execute(status -> {
            status.setRollbackOnly();
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, customer, at, at));
        });

        assertThat(plan).contains("ix_booking_restrictions_customer_ends_at");
    }

    // ================================================================ helpers

    private String plan(String method, Class<?>[] types, Object... args) {
        try {
            String sql = AppointmentRepository.class.getMethod(method, types).getAnnotation(Query.class).value()
                    .replaceAll(":[a-zA-Z]+", "?");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Một luồng giữ khóa ({@code lockSql}) trong transaction riêng; các request bắn lần lượt, mỗi request phải đứng chờ
     * khóa ({@code pg_stat_activity}, {@code waitingLike}) trước request kế tiếp. Rồi luồng giữ khóa chạy
     * {@code beforeRelease} trong chính transaction của nó và commit. {@code expectedWaiting = 0}: request không được chờ.
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

    private static String petLockSql() {
        return "SELECT pg_advisory_xact_lock(?, ?)::text";
    }

    private static Object[] petLockArgs(long petId) {
        return new Object[] {1001, (int) (petId % Integer.MAX_VALUE)};
    }

    private long count() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM appointments a JOIN pets p ON p.id = a.pet_id
                WHERE p.customer_id IN (SELECT id FROM customers WHERE full_name = 'Nguyễn Văn An' AND account_id = ?)
                """, Long.class, customerAccount);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "BookAppointmentIT")).accessToken();
    }

    private static Map<String, Object> body(long petId, long branchId, long serviceId, LocalDate date, String start,
            String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", petId);
        body.put("branchId", branchId);
        body.put("serviceId", serviceId);
        body.put("slotDate", date.toString());
        body.put("slotStart", start);
        body.put("note", note);
        return body;
    }

    private ResponseEntity<Map<String, Object>> post(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> postRaw(String token, String body, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> created(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsOnlyKeys(APPOINTMENT_KEYS);
        return data;
    }

    private void assertRule(ResponseEntity<Map<String, Object>> response, String message) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", message);
    }

    private static Map<String, Object> withoutVolatile(Map<String, Object> body) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove("timestamp");
        copy.remove("traceId");
        return copy;
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
}
