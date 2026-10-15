package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * T16 phần 3 — xem lịch hẹn {@code GET /api/appointments} (#3) và {@code GET /api/appointments/{id}} (#4)
 * (appointment-v1, UC40, UC44; docs/adr/0044) qua HTTP trên Postgres 17 thật. {@code BranchQueryService},
 * {@code CatalogQueryService} thật; {@code PetQueryApi} / {@code CustomerQueryApi} (BE-2, placeholder) thay bằng
 * {@link JdbcPetCustomerModule} (SQL mỗi lần gọi, đếm số lần gọi theo lô).
 * <p>
 * Đồng hồ {@code T0} = 2026-10-15 09:00 giờ Việt Nam. Mỗi test có chi nhánh, khách, thú riêng (tên UUID), nên danh sách
 * của chi nhánh / của khách chỉ chứa dòng do chính test chèn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AppointmentViewIT.TestBeans.class})
class AppointmentViewIT {

    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final List<String> APPOINTMENT_KEYS = List.of("appointmentId", "code", "customerId", "customerName",
            "petId", "petName", "branchId", "branchName", "serviceId", "serviceName", "serviceGroup", "slotDate",
            "slotStart", "status", "channel", "rescheduleCount", "lateCancel", "cancelSource", "cancelReason",
            "cancelledAt", "note", "visitId", "canReschedule", "canCancel");
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
    private JdbcPetCustomerModule module;

    @Autowired
    private SystemConfigService configs;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long manager;
    private long customerAccount;
    private long customer;
    private long pet;
    private long otherCustomer;
    private long otherPet;
    private String receptionistToken;
    private String managerToken;
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
        receptionist = data.staff("RECEPTIONIST", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        customerAccount = data.customerAccount();
        customer = data.onlineCustomer(customerAccount, "Nguyễn Văn An");
        pet = data.pet(customer);
        otherCustomer = data.counterCustomer();
        otherPet = data.pet(otherCustomer);
        receptionistToken = token(receptionist);
        managerToken = token(manager);
        customerToken = token(customerAccount);
        module.resetCounters();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ================================================================ hình dạng response, mapping

    @Test
    void listRowMatchesTheDatabaseColumnByColumnAndDetailIsTheSame() {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(10, 30), "CANCELLED");
        jdbc.update("""
                UPDATE appointments SET reschedule_count = 2, late_cancel = true, cancel_source = 'STAFF',
                       cancel_reason = 'Khách bận', cancelled_at = ?, note = 'Ghi chú khách', channel = 'ONLINE'
                WHERE id = ?""", Timestamp.from(Instant.parse("2026-10-15T01:02:03Z")), id);
        jdbc.update("UPDATE pets SET name = 'Bông' WHERE id = ?", pet);

        ResponseEntity<Map<String, Object>> response = list(receptionistToken, "");
        Map<String, Object> page = page(response);
        Map<String, Object> row = only(page);

        assertThat(response.getBody()).containsEntry("message", "success").containsEntry("code", 200);
        String code = jdbc.queryForObject("SELECT code FROM appointments WHERE id = ?", String.class, id);
        String branchName = jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch);
        String serviceName = jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, medical);
        assertThat(row)
                .containsEntry("appointmentId", (int) id).containsEntry("code", code)
                .containsEntry("customerId", (int) customer).containsEntry("customerName", "Nguyễn Văn An")
                .containsEntry("petId", (int) pet).containsEntry("petName", "Bông")
                .containsEntry("branchId", (int) branch).containsEntry("branchName", branchName)
                .containsEntry("serviceId", (int) medical).containsEntry("serviceName", serviceName)
                .containsEntry("serviceGroup", "MEDICAL").containsEntry("slotDate", "2026-10-17")
                .containsEntry("slotStart", "10:30").containsEntry("status", "CANCELLED")
                .containsEntry("channel", "ONLINE").containsEntry("rescheduleCount", 2)
                .containsEntry("lateCancel", true).containsEntry("cancelSource", "STAFF")
                .containsEntry("cancelReason", "Khách bận").containsEntry("cancelledAt", "2026-10-15T01:02:03Z")
                .containsEntry("note", "Ghi chú khách").containsEntry("visitId", null)
                .containsEntry("canReschedule", false).containsEntry("canCancel", false);

        Map<String, Object> detail = detail(receptionistToken, id);
        assertThat(detail).isEqualTo(row);
    }

    @Test
    void bookedRowCarriesNullKeysAndViewerFlags() {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(10, 30), "BOOKED");

        Map<String, Object> asCustomer = only(page(list(customerToken, "")));
        Map<String, Object> asReceptionist = only(page(list(receptionistToken, "")));
        Map<String, Object> asManager = only(page(list(managerToken, "")));
        Map<String, Object> asVet = detail(token(data.staff("VET", branch)), id);
        Map<String, Object> asCaretaker = detail(token(data.staff("CARETAKER", branch)), id);

        assertThat(asCustomer).containsEntry("cancelSource", null).containsEntry("cancelReason", null)
                .containsEntry("cancelledAt", null).containsEntry("note", null).containsEntry("visitId", null)
                .containsEntry("canReschedule", true).containsEntry("canCancel", true);
        assertThat(asReceptionist).containsEntry("canReschedule", true).containsEntry("canCancel", true);
        for (Map<String, Object> readOnly : List.of(asManager, asVet, asCaretaker)) {
            assertThat(readOnly).containsEntry("canReschedule", false).containsEntry("canCancel", false);
        }
    }

    @Test
    void customerNeedsTwelveHoursToRescheduleWhileReceptionistDoesNot() {
        // 20:30 hôm nay: còn 11 giờ 30 phút lúc 09:00.
        insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(20, 30), "BOOKED");

        assertThat(only(page(list(customerToken, "")))).containsEntry("canReschedule", false)
                .containsEntry("canCancel", true);
        assertThat(only(page(list(receptionistToken, "")))).containsEntry("canReschedule", true)
                .containsEntry("canCancel", true);

        clock.set(Instant.parse("2026-10-15T01:30:00Z")); // 08:30 → đúng 12 giờ, biên tính là được
        assertThat(only(page(list(customerToken, "")))).containsEntry("canReschedule", true);
    }

    @Test
    void namesAreJoinedByEachRowsIdsWithOneBatchCallPerPage() {
        long secondPet = data.pet(customer);
        long thirdPet = data.pet(otherCustomer);
        jdbc.update("UPDATE pets SET name = 'Một' WHERE id = ?", pet);
        jdbc.update("UPDATE pets SET name = 'Hai' WHERE id = ?", secondPet);
        jdbc.update("UPDATE pets SET name = 'Ba' WHERE id = ?", thirdPet);
        insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        insert(customer, secondPet, branch, grooming, "GROOMING", DAY, LocalTime.of(8, 30), "BOOKED");
        insert(otherCustomer, thirdPet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "BOOKED");
        insert(customer, pet, branch, grooming, "GROOMING", DAY, LocalTime.of(9, 30), "BOOKED");
        insert(otherCustomer, thirdPet, branch, grooming, "GROOMING", DAY, LocalTime.of(10, 0), "BOOKED");
        module.resetCounters();

        List<Map<String, Object>> rows = content(page(list(receptionistToken, "")));

        assertThat(rows).hasSize(5);
        for (Map<String, Object> row : rows) {
            long petId = ((Number) row.get("petId")).longValue();
            long customerId = ((Number) row.get("customerId")).longValue();
            long serviceId = ((Number) row.get("serviceId")).longValue();
            assertThat(row.get("petName")).isEqualTo(
                    jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, petId));
            assertThat(row.get("customerName")).isEqualTo(
                    jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, customerId));
            assertThat(row.get("serviceName")).isEqualTo(
                    jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, serviceId));
        }
        assertThat(module.findPetsCalls.get()).isEqualTo(1);
        assertThat(module.findContactsCalls.get()).isEqualTo(1);
    }

    @Test
    void emptyPageCallsNoBatch() {
        Map<String, Object> page = page(list(receptionistToken, ""));

        assertThat(content(page)).isEmpty();
        assertThat(page).containsEntry("totalElements", 0).containsEntry("totalPages", 0);
        assertThat(module.findPetsCalls.get()).isZero();
        assertThat(module.findContactsCalls.get()).isZero();
    }

    @Test
    void customerKeepsAppointmentsBookedBeforeAPetTransferAndTheNewOwnerDoesNotSeeThem() {
        insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "CANCELLED");
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", otherCustomer, pet); // BR-KH-08 giả lập
        long newOwnerAccount = data.customerAccount();
        jdbc.update("UPDATE customers SET account_id = ? WHERE id = ?", newOwnerAccount, otherCustomer);

        Map<String, Object> row = only(page(list(customerToken, "")));
        assertThat(row).containsEntry("customerId", (int) customer).containsEntry("customerName", "Nguyễn Văn An");
        assertThat(content(page(list(token(newOwnerAccount), "")))).isEmpty();
    }

    @Test
    void discontinuedServiceStillShowsItsName() {
        insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "COMPLETED");
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", medical);

        assertThat(only(page(list(customerToken, "")))).containsEntry("serviceName",
                jdbc.queryForObject("SELECT name FROM services WHERE id = ?", String.class, medical));
    }

    // ================================================================ phạm vi

    @Test
    void customerSeesOnlyOwnAppointmentsAcrossBranchesAndFiltersOnlyNarrowThem() {
        long own1 = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        long own2 = insert(customer, pet, otherBranch, grooming, "GROOMING", DAY, LocalTime.of(9, 0), "BOOKED");
        long foreign = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        assertThat(ids(list(customerToken, ""))).containsExactly(own1, own2);
        assertThat(ids(list(customerToken, "branchId=" + otherBranch))).containsExactly(own2);
        assertThat(ids(list(customerToken, "customerId=" + otherCustomer))).isEmpty();
        assertThat(ids(list(customerToken, "petId=" + otherPet))).isEmpty();
        assertThat(ids(list(customerToken, "customerId=" + customer))).containsExactly(own1, own2);
        assertThat(ids(list(receptionistToken, ""))).containsExactly(own1, foreign);
    }

    @Test
    void staffSeeOnlyTheirBranchAndAnotherBranchIs403() {
        long here = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        insert(otherCustomer, otherPet, otherBranch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        for (String token : List.of(receptionistToken, managerToken)) {
            assertThat(ids(list(token, ""))).containsExactly(here);
            assertThat(ids(list(token, "branchId=" + branch))).containsExactly(here);
            assertError(list(token, "branchId=" + otherBranch), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH",
                    null);
            assertError(list(token, "branchId=0"), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VET", "CARETAKER", "ADMIN", "SUPER_MANAGER"})
    void rolesOutsideTheListContractAre403(String role) {
        Long staffBranch = role.equals("ADMIN") || role.equals("SUPER_MANAGER") ? null : branch;

        assertError(list(token(data.staff(role, staffBranch)), ""), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER"})
    void chainRolesCannotOpenADetail(String role) {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        assertError(get(token(data.staff(role, null)), "/" + id), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void anotherCustomersAppointmentLooksExactlyLikeAMissingOne() {
        long foreign = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        long missing = foreign + 1_000_000;

        ResponseEntity<Map<String, Object>> hidden = get(customerToken, "/" + foreign);
        ResponseEntity<Map<String, Object>> absent = get(customerToken, "/" + missing);

        assertError(hidden, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy lịch hẹn #" + foreign);
        assertError(absent, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy lịch hẹn #" + missing);
        assertError(get(customerToken, "/0"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy lịch hẹn #0");
    }

    @Test
    void staffOfAnotherBranchGet403OnADetail() {
        long elsewhere = insert(customer, pet, otherBranch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        for (String role : List.of("RECEPTIONIST", "BRANCH_MANAGER", "VET", "CARETAKER")) {
            assertError(get(token(data.staff(role, branch)), "/" + elsewhere), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED_SCOPE_MISMATCH", null);
        }
        assertThat(detail(customerToken, elsewhere)).containsEntry("appointmentId", (int) elsewhere);
    }

    // ================================================================ bộ lọc, sắp xếp, trang

    @Test
    void everyFilterIsAnAndAndDatesAreInclusive() {
        long a = insert(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2026, 10, 16), LocalTime.of(8, 0),
                "BOOKED");
        long b = insert(customer, pet, branch, grooming, "GROOMING", DAY, LocalTime.of(8, 0), "BOOKED");
        long c = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "CANCELLED");
        long d = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", LocalDate.of(2026, 10, 18),
                LocalTime.of(8, 0), "NO_SHOW");

        assertThat(ids(list(receptionistToken, "date=2026-10-17"))).containsExactly(b, c);
        assertThat(ids(list(receptionistToken, "from=2026-10-17"))).containsExactly(b, c, d);
        assertThat(ids(list(receptionistToken, "to=2026-10-17"))).containsExactly(a, b, c);
        assertThat(ids(list(receptionistToken, "from=2026-10-16&to=2026-10-17"))).containsExactly(a, b, c);
        assertThat(ids(list(receptionistToken, "from=2026-10-18&to=2026-10-16"))).isEmpty();
        assertThat(ids(list(receptionistToken, "status=CANCELLED"))).containsExactly(c);
        assertThat(ids(list(receptionistToken, "status=NO_SHOW"))).containsExactly(d);
        assertThat(ids(list(receptionistToken, "petId=" + pet))).containsExactly(a, b);
        assertThat(ids(list(receptionistToken, "customerId=" + otherCustomer))).containsExactly(c, d);
        assertThat(ids(list(receptionistToken, "customerId=" + otherCustomer + "&status=BOOKED"))).isEmpty();
        assertThat(ids(list(receptionistToken, "date=2026-10-17&from=2026-10-18"))).isEmpty();
    }

    @Test
    void sortedByDateThenTimeThenId() {
        long late = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(11, 0), "BOOKED");
        long tieFirst = insert(customer, pet, branch, grooming, "GROOMING", DAY, LocalTime.of(9, 0), "CANCELLED");
        long tieSecond = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "BOOKED");
        long earlierDay = insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(15, 0), "BOOKED");

        assertThat(ids(list(receptionistToken, ""))).containsExactly(earlierDay, tieFirst, tieSecond, late);
    }

    @Test
    void pagingIsClampedAndCountsTheWholeFilter() {
        for (int i = 0; i < 3; i++) {
            insert(customer, pet, branch, medical, "MEDICAL", DAY.plusDays(i), LocalTime.of(8, 0), "BOOKED");
        }

        Map<String, Object> first = page(list(receptionistToken, "size=0"));
        assertThat(first).containsEntry("page", 0).containsEntry("size", 1).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 3);
        assertThat(content(first)).hasSize(1);
        assertThat(page(list(receptionistToken, "size=1000"))).containsEntry("size", 100);
        assertThat(page(list(receptionistToken, "page=-1&size=2"))).containsEntry("page", 0);
        Map<String, Object> beyond = page(list(receptionistToken, "page=5&size=2"));
        assertThat(content(beyond)).isEmpty();
        assertThat(beyond).containsEntry("totalElements", 3);
        assertThat(content(page(list(receptionistToken, "page=1&size=2")))).hasSize(1);
    }

    // ================================================================ checkInReady (BR-TN-02, 03, BR-LH-08)

    @Test
    void checkInReadyKeepsOnlyTodaysBookedSlotsInsideTheWindow() {
        long justOpened = insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(9, 30), "BOOKED");
        long stillLate = insert(otherCustomer, otherPet, branch, medical, "MEDICAL", TODAY, LocalTime.of(8, 30),
                "BOOKED");
        insert(customer, pet, branch, grooming, "GROOMING", TODAY, LocalTime.of(10, 0), "BOOKED"); // quá sớm
        insert(otherCustomer, otherPet, branch, grooming, "GROOMING", TODAY, LocalTime.of(8, 0), "BOOKED"); // quá muộn
        insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(9, 0), "CHECKED_IN");
        insert(customer, pet, branch, medical, "MEDICAL", TODAY.plusDays(1), LocalTime.of(9, 0), "BOOKED");
        insert(otherCustomer, otherPet, otherBranch, medical, "MEDICAL", TODAY, LocalTime.of(9, 0), "BOOKED");

        assertThat(ids(list(receptionistToken, "checkInReady=true"))).containsExactly(stillLate, justOpened);
        assertThat(ids(list(receptionistToken, "checkInReady=1"))).containsExactly(stillLate, justOpened);
        assertThat(ids(list(receptionistToken, "checkInReady=false"))).hasSize(6);
        assertThat(ids(list(receptionistToken, "checkInReady="))).hasSize(6);
        assertThat(ids(list(receptionistToken, "checkInReady=true&status=CHECKED_IN"))).isEmpty();
        assertThat(ids(list(receptionistToken, "checkInReady=true&petId=" + pet))).containsExactly(justOpened);

        clock.set(T0.plusSeconds(1)); // 09:00:01 → 08:30 đã quá 30 phút
        assertThat(ids(list(receptionistToken, "checkInReady=true"))).containsExactly(justOpened);
        clock.set(T0.minusSeconds(1)); // 08:59:59 → 09:30 chưa tới 30 phút trước
        assertThat(ids(list(receptionistToken, "checkInReady=true"))).containsExactly(stillLate);
    }

    @Test
    void checkInReadyFollowsTheConfiguredMinutes() {
        long ten = insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(10, 0), "BOOKED");
        try {
            jdbc.update("UPDATE system_configs SET value = '60' WHERE key = 'visit.check_in_early_minutes'");
            configs.reload();

            assertThat(ids(list(receptionistToken, "checkInReady=true"))).containsExactly(ten);
        } finally {
            jdbc.update("UPDATE system_configs SET value = '30' WHERE key = 'visit.check_in_early_minutes'");
            configs.reload();
        }
        assertThat(ids(list(receptionistToken, "checkInReady=true"))).isEmpty();
    }

    // ================================================================ tham số, thứ tự lỗi

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "branchId=abc                  | branchId",
            "petId=1.5                     | petId",
            "customerId=99999999999999999999 | customerId",
            "date=15/10/2026               | date",
            "from=2026-02-30               | from",
            "to=2026-13-01                 | to",
            "status=FOO                    | status",
            "status=booked                 | status",
            "checkInReady=maybe            | checkInReady",
            "page=x                        | page",
            "size=1.5                      | size"})
    void malformedParametersAre400(String query, String parameter) {
        assertError(list(receptionistToken, query), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số '" + parameter + "' sai kiểu dữ liệu");
    }

    @Test
    void malformedDetailIdIs400() {
        assertError(get(customerToken, "/abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'appointmentId' sai kiểu dữ liệu");
        assertError(get(customerToken, "/99999999999999999999"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'appointmentId' sai kiểu dữ liệu");
    }

    @Test
    void blankOptionalParametersMeanNotSentAndUnknownOnesAreIgnored() {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        assertThat(ids(list(receptionistToken,
                "branchId=&date=&from=&to=&status=&petId=&customerId=&checkInReady=&accountId=1&foo=bar")))
                .containsExactly(id);
    }

    @Test
    void repeatedStatusKeepsTheFirstValue() {
        long booked = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        insert(customer, pet, branch, grooming, "GROOMING", DAY, LocalTime.of(9, 0), "CANCELLED");

        assertThat(ids(list(receptionistToken, "status=BOOKED&status=CANCELLED"))).containsExactly(booked);
    }

    @Test
    void parametersAreCheckedBeforeTheRole() {
        String vetToken = token(data.staff("VET", branch));

        assertError(list(vetToken, "status=FOO"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(list(vetToken, ""), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void mustChangePasswordWinsOverMalformedParametersAndTheRole() {
        long vet = data.staff("VET", branch);
        String vetToken = token(vet);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);

        for (ResponseEntity<Map<String, Object>> response : List.of(list(vetToken, "status=FOO"),
                list(receptionistToken, ""), get(receptionistToken, "/abc"))) {
            assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
        }
    }

    @Test
    void anonymousIs401AndOtherMethodsAre405() {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");

        assertError(list(null, ""), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(get(null, "/" + id), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(exchange(HttpMethod.PUT, receptionistToken, "/" + id), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.DELETE, receptionistToken, "/" + id), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
    }

    // ================================================================ chỉ đọc

    @Test
    void readingWritesNothing() {
        long id = insert(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(8, 0), "BOOKED");
        Map<String, Object> before = jdbc.queryForMap("SELECT *, xmin::text AS row_version FROM appointments WHERE id = ?",
                id);
        long audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);

        list(customerToken, "");
        list(receptionistToken, "checkInReady=true");
        detail(receptionistToken, id);
        list(token(data.staff("VET", branch)), ""); // 403

        assertThat(jdbc.queryForMap("SELECT *, xmin::text AS row_version FROM appointments WHERE id = ?", id))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(audits);
    }

    // ================================================================ kế hoạch truy vấn (nợ D032)

    /**
     * EXPLAIN trên 20 000 dòng chèn trong một transaction rollback + {@code ANALYZE}, với câu SQL tương đương câu
     * Specification sinh ra (giá trị literal). Hai dạng nóng — danh sách của khách và danh sách chờ check-in — không được
     * quét toàn bảng. Danh sách cả ngày của chi nhánh (mọi trạng thái) không có index riêng: chỉ ghi lại, xem D032.
     */
    @Test
    void hotQueriesUseAnIndex() {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group,
                            slot_date, slot_start, status, channel, booked_by)
                    SELECT 'LH-EX-' || g, ?, ?, CASE WHEN g % 2 = 0 THEN ? ELSE ? END, ?, 'MEDICAL',
                           DATE '2024-01-01' + (g % 700), TIME '08:00' + (g % 8) * INTERVAL '30 minutes',
                           'COMPLETED', 'COUNTER', ?
                    FROM generate_series(1, 20000) g""", otherCustomer, otherPet, branch, otherBranch, medical,
                    receptionist);
            insert(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(9, 0), "BOOKED");
            jdbc.execute("ANALYZE appointments");

            String customerPlan = plan("SELECT * FROM appointments WHERE customer_id = " + customer
                    + " ORDER BY slot_date, slot_start, id LIMIT 20");
            String checkInPlan = plan("SELECT * FROM appointments WHERE branch_id = " + branch
                    + " AND status = 'BOOKED' AND slot_date = DATE '2026-10-15' AND slot_start >= TIME '08:30'"
                    + " AND slot_start <= TIME '09:30' ORDER BY slot_date, slot_start, id LIMIT 20");
            String dayPlan = plan("SELECT * FROM appointments WHERE branch_id = " + branch
                    + " AND slot_date = DATE '2026-10-15' ORDER BY slot_date, slot_start, id LIMIT 20");

            assertThat(customerPlan).as(customerPlan).doesNotContain("Seq Scan on appointments");
            assertThat(checkInPlan).as(checkInPlan).doesNotContain("Seq Scan on appointments");
            // Nợ D032: chưa có index bắt đầu bằng (branch_id, slot_date) — branch_id chỉ là Filter. Trả nợ thì đổi assert.
            assertThat(dayPlan).as(dayPlan).contains("Filter: (branch_id = " + branch + ")");
            status.setRollbackOnly();
        });
    }

    // ================================================================ helpers

    private long insert(long customerId, long petId, long branchId, long serviceId, String group, LocalDate date,
            LocalTime start, String status) {
        return data.appointment(customerId, petId, branchId, serviceId, group, date, start, status, receptionist);
    }

    private String plan(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AppointmentViewIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> list(String token, String query) {
        return get(token, query.isEmpty() ? "" : "?" + query);
    }

    private ResponseEntity<Map<String, Object>> get(String token, String suffix) {
        return exchange(HttpMethod.GET, token, suffix);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String token, String suffix) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments" + suffix), method,
                new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> page(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        Map<String, Object> page = (Map<String, Object>) response.getBody().get("data");
        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages");
        for (Map<String, Object> row : content(page)) {
            assertThat(row).containsOnlyKeys(APPOINTMENT_KEYS);
        }
        return page;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    private Map<String, Object> only(Map<String, Object> page) {
        assertThat(content(page)).hasSize(1);
        return content(page).get(0);
    }

    private List<Long> ids(ResponseEntity<Map<String, Object>> response) {
        return content(page(response)).stream().map(row -> ((Number) row.get("appointmentId")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(String token, long id) {
        ResponseEntity<Map<String, Object>> response = get(token, "/" + id);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsOnlyKeys(APPOINTMENT_KEYS);
        return new HashMap<>(data);
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
