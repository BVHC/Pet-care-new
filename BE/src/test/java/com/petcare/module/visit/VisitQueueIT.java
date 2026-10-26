package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Xem lượt tiếp nhận — visit-v1 #2 {@code GET /api/visits}, #3 {@code GET /api/me/queue}, #4 {@code GET /api/visits/{id}}
 * (docs/adr/0060) trên Postgres 17 thật, qua HTTP.
 * <ul>
 * <li>Mapping từng cột (E3), thứ tự BR-TN-04, bộ lọc A1 (Q1) gồm ranh giới ngày giờ Việt Nam.</li>
 * <li>Validate / exception / thứ tự lỗi (D2, D4), phân quyền 7 role × 3 endpoint và đổi DB cùng token (A2, A4).</li>
 * <li>Transaction (F4): móc giữa transaction là spy {@link OrderQueryApi} — bean thường, không proxy CGLIB — được gọi
 * sau câu {@code visits} và trước lô thú / khách / nhân viên (F-F2).</li>
 * </ul>
 * Đồng hồ {@link MutableClock} ở <b>năm 2005</b> (chưa IT nào dùng). {@code TODAY} = 2005-03-15, {@code T0} = 10:00 giờ
 * Việt Nam; chi nhánh mở 08:00–12:00 và 14:00–19:00. Dữ liệu lượt chèn bằng SQL, dọn theo chi nhánh của test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VisitQueueIT.TestBeans.class})
class VisitQueueIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2005, 3, 15);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final Instant T0 = at(TODAY, 10, 0);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String[] VISIT_KEYS = {"visitId", "code", "branchId", "customerId", "customerName", "petId",
        "petName", "species", "appointmentId", "serviceGroup", "status", "priorityClass", "queueSortKey", "assigneeId",
        "assigneeName", "needsReassign", "orderId", "checkedInAt", "calledAt", "completedAt", "cancelledAt",
        "cancelReason"};
    private static final String TX_STATE = """
            SELECT current_setting('transaction_isolation') || '|' || current_setting('transaction_read_only')
                   || '|' || pg_backend_pid()""";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @MockitoSpyBean
    private OrderQueryApi orderQueries;

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
    private DataSource dataSource;

    private volatile Runnable insideOrderLookup;
    private volatile RuntimeException failOrderLookup;
    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> retailOrders = new ArrayList<>();
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long manager;
    private long vet;
    private long caretaker;
    private long otherVet;
    private long customer;
    private long pet;
    private String receptionistToken;
    private String vetToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = activeBranch();
        otherBranch = activeBranch();
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        data.branchService(branch, medical, true);
        data.branchService(branch, grooming, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        vet = data.staff("VET", branch);
        caretaker = data.staff("CARETAKER", branch);
        otherVet = data.staff("VET", otherBranch);
        customer = data.counterCustomer("Khách-" + UUID.randomUUID(), null);
        pet = namedPet(customer, "DOG");
        receptionistToken = token(receptionist);
        vetToken = token(vet);

        Answer<?> real = mockingDetails(orderQueries).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Runnable hook = insideOrderLookup;
            if (hook != null) {
                insideOrderLookup = null;
                hook.run();
            }
            RuntimeException failure = failOrderLookup;
            if (failure != null) {
                failOrderLookup = null;
                throw failure;
            }
            return real.answer(invocation);
        }).when(orderQueries).findOrderIdsByVisitIds(anyCollection());
    }

    @AfterEach
    void tearDown() {
        insideOrderLookup = null;
        failOrderLookup = null;
        for (long id : retailOrders) {
            jdbc.update("DELETE FROM orders WHERE id = ?", id);
        }
        for (long b : branches) {
            jdbc.update("""
                    DELETE FROM order_lines WHERE order_id IN (
                        SELECT o.id FROM orders o JOIN visits v ON v.id = o.visit_id WHERE v.branch_id = ?)""", b);
            jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", b);
            jdbc.update("DELETE FROM visits WHERE branch_id = ?", b);
        }
        data.cleanUp();
    }

    // ================================================================ E — mapping từng cột

    @Test
    void listRowMatchesTheDatabaseColumnByColumnAndDetailIsTheSame() {
        long otherCustomer = data.counterCustomer("Khách-" + UUID.randomUUID(), null);
        long cat = namedPet(otherCustomer, "CAT");
        long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", TODAY, LocalTime.of(9, 30),
                "CHECKED_IN", receptionist);
        long waiting = seed().at(9, 10).insert();
        long inProgress = seed().appointment(appointment).priority("APPOINTMENT").sortKey(at(TODAY, 9, 30))
                .status("IN_PROGRESS").assignee(vet).called(at(TODAY, 9, 40)).needsReassign().at(9, 25).insert();
        long completed = seed().customer(otherCustomer).pet(cat).group("GROOMING").status("COMPLETED")
                .assignee(caretaker).called(at(TODAY, 9, 5)).completed(at(TODAY, 9, 50)).at(9, 0).insert();
        long cancelled = seed().status("CANCELLED").cancelled(at(TODAY, 9, 20), "Lý do-" + UUID.randomUUID())
                .at(9, 15).insert();
        retailOrder(customer);

        ResponseEntity<Map<String, Object>> response = get(receptionistToken, "/api/visits?date=" + TODAY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code").containsEntry("code", 200)
                .containsEntry("message", "success");
        List<Map<String, Object>> rows = list(response);
        assertThat(ids(rows)).containsExactlyInAnyOrder(waiting, inProgress, completed, cancelled);
        for (Map<String, Object> row : rows) {
            assertThat(row).containsOnlyKeys(VISIT_KEYS);
            assertMatchesDatabase(row);
            assertThat(detail(receptionistToken, ((Number) row.get("visitId")).longValue()))
                    .as("chi tiết = phần tử của danh sách").isEqualTo(row);
        }
        Map<String, Object> fresh = rows.stream().filter(row -> id(row) == waiting).findFirst().orElseThrow();
        assertThat(fresh).containsEntry("assigneeId", null).containsEntry("assigneeName", null)
                .containsEntry("appointmentId", null).containsEntry("calledAt", null)
                .containsEntry("needsReassign", false);
    }

    @Test
    void customerNameIsTheOwnerAtCheckIn() {
        long id = seed().status("COMPLETED").assignee(vet).called(at(TODAY, 9, 5)).completed(at(TODAY, 9, 30))
                .at(9, 0).insert();
        String ownerAtCheckIn = jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class,
                customer);
        long newOwner = data.counterCustomer("Chủ mới-" + UUID.randomUUID(), null);
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner, pet);
        try {
            Map<String, Object> visit = detail(receptionistToken, id);

            assertThat(visit).containsEntry("customerName", ownerAtCheckIn);
            assertThat(((Number) visit.get("customerId")).longValue()).isEqualTo(customer);
        } finally {
            jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", customer, pet);
        }
    }

    @Test
    void checkInResponseEqualsDetailAndIsVisibleOnTheNextRead() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("serviceId", medical);
        ResponseEntity<Map<String, Object>> created = exchange(HttpMethod.POST, receptionistToken, "/api/visits", body);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> posted = (Map<String, Object>) created.getBody().get("data");

        assertThat(detail(receptionistToken, id(posted))).isEqualTo(posted);
        assertThat(ids(list(get(receptionistToken, "/api/visits")))).contains(id(posted));
    }

    // ================================================================ BR-TN-04, A1 (Q1)

    @Test
    void queueFollowsBrTn04() {
        long walkInEarly = seed().at(9, 0).insert();
        long walkInTieLow = seed().at(9, 30).insert();
        long walkInTieHigh = seed().at(9, 30).insert();
        long appointmentLate = seed().priority("APPOINTMENT").sortKey(at(TODAY, 10, 30)).at(9, 50).insert();
        long appointmentEarly = seed().priority("APPOINTMENT").sortKey(at(TODAY, 10, 0)).at(9, 40).insert();
        long emergencyLate = seed().priority("EMERGENCY").at(9, 55).insert();
        long emergencyEarly = seed().priority("EMERGENCY").at(9, 45).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits")))).containsExactly(emergencyEarly, emergencyLate,
                appointmentEarly, appointmentLate, walkInEarly, walkInTieLow, walkInTieHigh);
    }

    @Test
    void defaultQueueIsUnfinishedAndKeepsUnfinishedVisitsOfEarlierDays() {
        long oldWaiting = seed().on(YESTERDAY, 18, 0).insert();
        long oldInProgress = seed().status("IN_PROGRESS").assignee(vet).called(at(YESTERDAY, 18, 30))
                .on(YESTERDAY, 18, 10).insert();
        long oldCompleted = seed().status("COMPLETED").assignee(vet).called(at(YESTERDAY, 17, 0))
                .completed(at(YESTERDAY, 17, 30)).on(YESTERDAY, 16, 0).insert();
        long todayCompleted = seed().status("COMPLETED").assignee(vet).called(at(TODAY, 8, 30))
                .completed(at(TODAY, 9, 0)).at(8, 10).insert();
        long todayWaiting = seed().at(9, 0).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits"))))
                .containsExactlyInAnyOrder(oldWaiting, oldInProgress, todayWaiting);
        assertThat(ids(list(get(receptionistToken, "/api/visits?status=WAITING"))))
                .containsExactlyInAnyOrder(oldWaiting, todayWaiting);
        assertThat(ids(list(get(receptionistToken, "/api/visits?status=COMPLETED"))))
                .as("COMPLETED không gửi date = hôm nay").containsExactly(todayCompleted);
        assertThat(ids(list(get(receptionistToken, "/api/visits?date=" + YESTERDAY))))
                .containsExactlyInAnyOrder(oldWaiting, oldInProgress, oldCompleted);
        assertThat(ids(list(get(receptionistToken, "/api/visits?date=" + YESTERDAY + "&status=COMPLETED"))))
                .containsExactly(oldCompleted);
    }

    @Test
    void dateUsesVietnamDayBoundaries() {
        long lastMicroOfYesterday = seed().status("CANCELLED").cancelled(at(TODAY, 0, 5), null)
                .checkedInAt(TODAY.atStartOfDay(VN).toInstant().minusNanos(1_000)).insert();
        long firstMicroOfToday = seed().status("CANCELLED").cancelled(at(TODAY, 0, 5), null)
                .checkedInAt(TODAY.atStartOfDay(VN).toInstant()).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits?date=" + TODAY)))).containsExactly(firstMicroOfToday);
        assertThat(ids(list(get(receptionistToken, "/api/visits?date=" + YESTERDAY))))
                .containsExactly(lastMicroOfYesterday);
    }

    @Test
    void filtersAreAndedWithBranchScope() {
        long medicalOfVet = seed().status("IN_PROGRESS").assignee(vet).called(at(TODAY, 9, 30)).needsReassign()
                .at(9, 0).insert();
        long groomingOfCaretaker = seed().group("GROOMING").status("IN_PROGRESS").assignee(caretaker)
                .called(at(TODAY, 9, 30)).at(9, 5).insert();
        long unassigned = seed().at(9, 10).insert();
        long otherBranchVisit = seed().branch(otherBranch).at(9, 15).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits?serviceGroup=GROOMING"))))
                .containsExactly(groomingOfCaretaker);
        assertThat(ids(list(get(receptionistToken, "/api/visits?assigneeId=" + vet)))).containsExactly(medicalOfVet);
        assertThat(ids(list(get(receptionistToken, "/api/visits?needsReassign=true")))).containsExactly(medicalOfVet);
        assertThat(ids(list(get(receptionistToken, "/api/visits?needsReassign=false"))))
                .containsExactlyInAnyOrder(groomingOfCaretaker, unassigned);
        assertThat(ids(list(get(receptionistToken, "/api/visits?serviceGroup=MEDICAL&assigneeId=" + caretaker))))
                .isEmpty();
        assertThat(ids(list(get(receptionistToken, "/api/visits?assigneeId=" + otherVet)))).isEmpty();
        assertThat(ids(list(get(receptionistToken, "/api/visits?assigneeId=-1")))).isEmpty();
        assertThat(ids(list(get(receptionistToken, "/api/visits?date=2099-01-01")))).isEmpty();
        assertThat(ids(list(get(receptionistToken, "/api/visits?branchId=" + otherBranch + "&foo=bar"))))
                .as("branchId lạ bị bỏ qua, không bao giờ thấy chi nhánh khác")
                .containsExactlyInAnyOrder(medicalOfVet, groomingOfCaretaker, unassigned)
                .doesNotContain(otherBranchVisit);
    }

    @Test
    void booleanAliasesFollowSpring() {
        long flagged = seed().status("IN_PROGRESS").assignee(vet).called(at(TODAY, 9, 30)).needsReassign().at(9, 0)
                .insert();
        seed().at(9, 10).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits?needsReassign=yes")))).containsExactly(flagged);
        assertThat(ids(list(get(receptionistToken, "/api/visits?needsReassign=1")))).containsExactly(flagged);
    }

    @Test
    void myQueueHoldsOnlyMyUnfinishedVisits() {
        long mineWaiting = seed().assignee(vet).at(9, 30).insert();
        long mineOldInProgress = seed().status("IN_PROGRESS").assignee(vet).called(at(YESTERDAY, 18, 30))
                .on(YESTERDAY, 18, 0).insert();
        seed().status("COMPLETED").assignee(vet).called(at(TODAY, 8, 30)).completed(at(TODAY, 9, 0)).at(8, 0)
                .insert();
        seed().status("CANCELLED").assignee(vet).cancelled(at(TODAY, 9, 5), null).at(8, 5).insert();
        seed().assignee(caretaker).group("GROOMING").at(9, 0).insert();
        seed().at(9, 1).insert();
        long mineEmergency = seed().assignee(vet).priority("EMERGENCY").at(9, 50).insert();

        assertThat(ids(list(get(vetToken, "/api/me/queue?status=FOO&assigneeId=" + caretaker))))
                .as("tham số lạ bị bỏ qua; cùng hạng WALK_IN thì lượt hôm qua (queueSortKey sớm hơn) trước")
                .containsExactly(mineEmergency, mineOldInProgress, mineWaiting);
    }

    // ================================================================ D — validate, exception, thứ tự lỗi

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "date=15/03/2005                 | date",
            "date=2005-02-30                 | date",
            "date=abc                        | date",
            "status=FOO                      | status",
            "status=waiting                  | status",
            "serviceGroup=BOARDING           | serviceGroup",
            "serviceGroup=medical            | serviceGroup",
            "assigneeId=abc                  | assigneeId",
            "assigneeId=1.5                  | assigneeId",
            "assigneeId=99999999999999999999 | assigneeId",
            "needsReassign=maybe             | needsReassign"})
    void malformedParametersAre400(String query, String parameter) {
        assertError(get(receptionistToken, "/api/visits?" + query), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số '" + parameter + "' sai kiểu dữ liệu");
    }

    @Test
    void blankParametersMeanNotSentAndRepeatedStatusKeepsTheFirstValue() {
        long waiting = seed().at(9, 0).insert();
        long cancelled = seed().status("CANCELLED").cancelled(at(TODAY, 9, 30), null).at(9, 10).insert();

        assertThat(ids(list(get(receptionistToken,
                "/api/visits?date=&status=&serviceGroup=&assigneeId=&needsReassign=")))).containsExactly(waiting);
        assertThat(ids(list(get(receptionistToken, "/api/visits?status=CANCELLED&status=WAITING"))))
                .containsExactly(cancelled);
    }

    @Test
    void malformedVisitIdIs400AndUnknownIs404() {
        assertError(get(receptionistToken, "/api/visits/abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'visitId' sai kiểu dữ liệu");
        assertError(get(receptionistToken, "/api/visits/99999999999999999999"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", "Tham số 'visitId' sai kiểu dữ liệu");
        assertError(get(receptionistToken, "/api/visits/-1"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy lượt tiếp nhận #-1");
        assertError(get(receptionistToken, "/api/visits/" + Long.MAX_VALUE), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", null);
    }

    @Test
    void otherBranchDetailIs403WithScopeCodeAndLeaksNothing() {
        String code = "IT05X-" + UUID.randomUUID().toString().substring(0, 8);
        long foreign = seed().branch(otherBranch).code(code).at(9, 0).insert();
        String petName = jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, pet);

        ResponseEntity<Map<String, Object>> response = get(vetToken, "/api/visits/" + foreign);

        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        // timestamp / traceId ngẫu nhiên có thể tình cờ chứa chuỗi số của id chi nhánh: chỉ xét phần còn lại.
        Map<String, Object> body = new java.util.HashMap<>(response.getBody());
        body.remove("timestamp");
        body.remove("traceId");
        assertThat(String.valueOf(body)).doesNotContain(code).doesNotContain(petName)
                .doesNotContain(String.valueOf(otherBranch));
    }

    @Test
    void unsupportedMethodsAre405() {
        long id = seed().at(9, 0).insert();

        assertError(exchange(HttpMethod.POST, vetToken, "/api/me/queue", null), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.PUT, receptionistToken, "/api/visits/" + id, Map.of()),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.DELETE, receptionistToken, "/api/visits", null),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
    }

    @Test
    void precedenceIsAuthenticationThenMustChangePasswordThenMalformedThenRoleThenNotFoundThenScope() {
        long foreign = seed().branch(otherBranch).at(9, 0).insert();
        String customerToken = token(data.customerAccount());

        assertError(get(customerToken, "/api/visits?status=FOO"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(get(customerToken, "/api/visits"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertError(get(customerToken, "/api/visits/abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(get(customerToken, "/api/visits/" + foreign), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertError(get(vetToken, "/api/visits/" + Long.MAX_VALUE), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
        assertError(get(vetToken, "/api/visits/" + foreign), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH",
                null);

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id IN (?, ?)", vet, receptionist);
        for (ResponseEntity<Map<String, Object>> response : List.of(get(vetToken, "/api/visits?status=FOO"),
                get(vetToken, "/api/me/queue"), get(vetToken, "/api/visits/abc"),
                get(receptionistToken, "/api/me/queue"))) {
            assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
        }
    }

    // ================================================================ A — xác thực, phân quyền

    @Test
    void anonymousGarbageAndRevokedTokensAre401() {
        long id = seed().at(9, 0).insert();
        for (String path : List.of("/api/visits", "/api/me/queue", "/api/visits/" + id)) {
            assertError(get(null, path), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
            assertError(get("garbage.token.value", path), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", vet);
        assertError(get(vetToken, "/api/me/queue"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
    }

    @Test
    void lockedOrDisabledStaffIs401WithTheSameToken() {
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", receptionist);

        assertError(get(vetToken, "/api/me/queue"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(get(receptionistToken, "/api/visits"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
    }

    @Test
    void queueRolesFollowTheContract() {
        long id = seed().at(9, 0).insert();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("ADMIN", token(data.staff("ADMIN", null)));
        tokens.put("SUPER_MANAGER", token(data.staff("SUPER_MANAGER", null)));
        tokens.put("CUSTOMER", token(data.customerAccount()));
        tokens.put("BRANCH_MANAGER", token(manager));
        tokens.put("RECEPTIONIST", receptionistToken);
        tokens.put("VET", vetToken);
        tokens.put("CARETAKER", token(caretaker));
        List<String> queueRoles = List.of("BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER");
        List<String> myQueueRoles = List.of("VET", "CARETAKER");
        // #4 thêm SUPER_MANAGER (visit-v1 A14, docs/adr/0063); #2 thì không.
        List<String> detailRoles = List.of("SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER");

        tokens.forEach((role, token) -> {
            assertAllowed(get(token, "/api/visits"), queueRoles.contains(role), role + " #2");
            assertAllowed(get(token, "/api/me/queue"), myQueueRoles.contains(role), role + " #3");
            assertAllowed(get(token, "/api/visits/" + id), detailRoles.contains(role), role + " #4");
        });
    }

    @Test
    void branchAndRoleComeFromTheDatabaseOnEveryRequest() {
        long here = seed().at(9, 0).insert();
        long there = seed().branch(otherBranch).at(9, 5).insert();

        assertThat(ids(list(get(receptionistToken, "/api/visits")))).containsExactly(here);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, receptionist);
        try {
            assertThat(ids(list(get(receptionistToken, "/api/visits")))).containsExactly(there);
            assertError(get(receptionistToken, "/api/visits/" + here), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED_SCOPE_MISMATCH", null);
        } finally {
            jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branch, receptionist);
        }

        assertThat(get(vetToken, "/api/me/queue").getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE accounts SET role = 'RECEPTIONIST' WHERE id = ?", vet);
        assertError(get(vetToken, "/api/me/queue"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertThat(get(vetToken, "/api/visits").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================================================================ F — transaction

    @Test
    void queueReadsRunInOneReadOnlyRepeatableReadTransaction() {
        long id = seed().assignee(vet).at(9, 0).insert();
        for (String path : List.of("/api/visits", "/api/me/queue", "/api/visits/" + id)) {
            List<String> states = new ArrayList<>();
            insideOrderLookup = () -> states.add(jdbc.queryForObject(TX_STATE, String.class));

            assertThat(get(vetToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);

            assertThat(states).as(path).hasSize(1);
            assertThat(states.get(0)).as(path).startsWith("repeatable read|on|");
        }
    }

    @Test
    void wholeResponseSeesOneSnapshot() {
        long id = seed().assignee(vet).at(9, 0).insert();
        String oldPet = jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, pet);
        String oldCustomer = jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, customer);
        String oldStaff = jdbc.queryForObject("SELECT full_name FROM staff_profiles WHERE account_id = ?", String.class,
                vet);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        List<Long> injected = new ArrayList<>();
        insideOrderLookup = () -> {
            autocommit("UPDATE pets SET name = 'Thú đổi " + suffix + "' WHERE id = " + pet);
            autocommit("UPDATE customers SET full_name = 'Khách đổi " + suffix + "' WHERE id = " + customer);
            autocommit("UPDATE staff_profiles SET full_name = 'NV đổi " + suffix + "' WHERE account_id = " + vet);
            injected.add(autocommitVisit());
        };

        List<Map<String, Object>> first = list(get(receptionistToken, "/api/visits"));

        assertThat(injected).hasSize(1);
        assertThat(ids(first)).as("lượt commit sau câu visits không lọt vào").containsExactly(id);
        assertThat(first.get(0)).containsEntry("petName", oldPet).containsEntry("customerName", oldCustomer)
                .containsEntry("assigneeName", oldStaff);

        List<Map<String, Object>> again = list(get(receptionistToken, "/api/visits"));
        assertThat(ids(again)).containsExactlyInAnyOrder(id, injected.get(0));
        Map<String, Object> same = again.stream().filter(row -> id(row) == id).findFirst().orElseThrow();
        assertThat(same).containsEntry("petName", "Thú đổi " + suffix)
                .containsEntry("customerName", "Khách đổi " + suffix).containsEntry("assigneeName", "NV đổi " + suffix);
    }

    @Test
    void connectionsReturnToPoolAsReadCommittedReadWrite() throws Exception {
        long id = seed().assignee(vet).at(9, 0).insert();
        for (int i = 0; i < 4; i++) {
            assertThat(get(vetToken, "/api/visits").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(vetToken, "/api/me/queue").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(vetToken, "/api/visits/" + id).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(vetToken, "/api/visits/" + Long.MAX_VALUE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                borrowed.add(hikari.getConnection());
            }
            for (Connection connection : borrowed) {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(connection.isReadOnly()).isFalse();
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("SELECT current_setting('transaction_isolation'), "
                                + "current_setting('transaction_read_only')")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("read committed");
                    assertThat(rs.getString(2)).isEqualTo("off");
                }
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
    }

    @Test
    void failureMidRequestIs500AndReleasesTheConnection() throws Exception {
        long id = seed().at(9, 0).insert();
        failOrderLookup = new DataAccessResourceFailureException("IT: DB tạm mất kết nối");

        assertError(get(receptionistToken, "/api/visits"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null);

        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(ids(list(get(receptionistToken, "/api/visits")))).containsExactly(id);
    }

    @Test
    void readsWriteNothing() {
        long waiting = seed().assignee(vet).at(9, 0).insert();
        long done = seed().status("COMPLETED").assignee(vet).called(at(TODAY, 8, 30)).completed(at(TODAY, 9, 0))
                .at(8, 0).insert();
        Map<String, Long> before = xmins(waiting, done);
        long audits = maxId("audit_logs");
        long outbox = maxId("notification_outbox");
        long assignments = jdbc.queryForObject("SELECT count(*) FROM visit_assignments", Long.class);

        get(receptionistToken, "/api/visits");
        get(receptionistToken, "/api/visits?date=" + TODAY);
        get(vetToken, "/api/me/queue");
        get(vetToken, "/api/visits/" + waiting);
        get(vetToken, "/api/visits/" + done);

        assertThat(xmins(waiting, done)).isEqualTo(before);
        assertThat(maxId("audit_logs")).as("xem không ghi audit").isEqualTo(audits);
        assertThat(maxId("notification_outbox")).isEqualTo(outbox);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_assignments", Long.class)).isEqualTo(assignments);
    }

    @Test
    void uncommittedCheckInIsInvisibleAndDoesNotBlock() throws Exception {
        long committed = seed().at(9, 0).insert();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(status -> {
                insertVisit(new Seed().at(9, 30));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<Long>> reader = pool.submit(() -> ids(list(get(receptionistToken, "/api/visits"))));

            assertThat(reader.get(5, TimeUnit.SECONDS)).as("không đọc bẩn, không chờ khóa").containsExactly(committed);
            release.countDown();
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ================================================================ dữ liệu

    /** Một lượt + Order VISIT của nó, mặc định: chi nhánh của test, khách / thú của test, MEDICAL, WAITING, WALK_IN. */
    private final class Seed {
        private long branchId = branch;
        private long customerId = customer;
        private long petId = pet;
        private Long appointmentId;
        private String group = "MEDICAL";
        private String status = "WAITING";
        private String priority = "WALK_IN";
        private Instant sortKey;
        private Long assigneeId;
        private boolean needsReassign;
        private Instant checkedInAt = T0;
        private Instant calledAt;
        private Instant completedAt;
        private Instant cancelledAt;
        private String cancelReason;
        private String code = "IT05-" + UUID.randomUUID().toString().substring(0, 12);

        Seed branch(long id) {
            branchId = id;
            return this;
        }

        Seed customer(long id) {
            customerId = id;
            return this;
        }

        Seed pet(long id) {
            petId = id;
            return this;
        }

        Seed appointment(long id) {
            appointmentId = id;
            return this;
        }

        Seed group(String value) {
            group = value;
            return this;
        }

        Seed status(String value) {
            status = value;
            return this;
        }

        Seed priority(String value) {
            priority = value;
            return this;
        }

        Seed sortKey(Instant value) {
            sortKey = value;
            return this;
        }

        Seed assignee(long id) {
            assigneeId = id;
            return this;
        }

        Seed needsReassign() {
            needsReassign = true;
            return this;
        }

        Seed called(Instant value) {
            calledAt = value;
            return this;
        }

        Seed completed(Instant value) {
            completedAt = value;
            return this;
        }

        Seed cancelled(Instant value, String reason) {
            cancelledAt = value;
            cancelReason = reason;
            return this;
        }

        Seed code(String value) {
            code = value;
            return this;
        }

        Seed checkedInAt(Instant value) {
            checkedInAt = value;
            return this;
        }

        Seed at(int hour, int minute) {
            return on(TODAY, hour, minute);
        }

        Seed on(LocalDate day, int hour, int minute) {
            checkedInAt = VisitQueueIT.at(day, hour, minute);
            return this;
        }

        long insert() {
            return insertVisit(this);
        }
    }

    private Seed seed() {
        return new Seed();
    }

    private long insertVisit(Seed seed) {
        Instant sortKey = seed.sortKey != null ? seed.sortKey : seed.checkedInAt;
        long id = jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, appointment_id, service_group, status,
                        priority_class, queue_sort_key, assignee_id, needs_reassign, checked_in_by, checked_in_at,
                        called_at, completed_at, cancelled_at, cancel_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                seed.code, seed.branchId, seed.customerId, seed.petId, seed.appointmentId, seed.group, seed.status,
                seed.priority, ts(sortKey), seed.assigneeId, seed.needsReassign, receptionist, ts(seed.checkedInAt),
                ts(seed.calledAt), ts(seed.completedAt), ts(seed.cancelledAt), seed.cancelReason);
        String orderStatus = switch (seed.status) {
            case "COMPLETED" -> "PENDING";
            case "CANCELLED" -> "CANCELLED";
            default -> "OPEN";
        };
        jdbc.update("""
                INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                VALUES (?, 'VISIT', ?, ?, ?, ?, ?)""", "OIT05-" + UUID.randomUUID().toString().substring(0, 12),
                seed.customerId, seed.branchId, id, orderStatus, receptionist);
        return id;
    }

    private void retailOrder(long customerId) {
        retailOrders.add(jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, created_by)
                VALUES (?, 'RETAIL', ?, ?, 'OPEN', ?) RETURNING id""", Long.class,
                "RIT05-" + UUID.randomUUID().toString().substring(0, 12), customerId, branch, receptionist));
    }

    private long namedPet(long customerId, String species) {
        long id = data.pet(customerId);
        jdbc.update("UPDATE pets SET name = ?, species = ? WHERE id = ?", "Thú-" + UUID.randomUUID(), species, id);
        return id;
    }

    private void assertMatchesDatabase(Map<String, Object> row) {
        long id = id(row);
        Map<String, Object> db = jdbc.queryForMap("""
                SELECT v.*, p.name AS pet_name, p.species AS pet_species, c.full_name AS customer_name,
                       sp.full_name AS assignee_name, o.id AS order_id
                FROM visits v
                JOIN pets p ON p.id = v.pet_id
                JOIN customers c ON c.id = v.customer_id
                LEFT JOIN staff_profiles sp ON sp.account_id = v.assignee_id
                JOIN orders o ON o.visit_id = v.id
                WHERE v.id = ?""", id);
        assertThat(row.get("code")).isEqualTo(db.get("code"));
        assertThat(number(row, "branchId")).isEqualTo(db.get("branch_id"));
        assertThat(number(row, "customerId")).isEqualTo(db.get("customer_id"));
        assertThat(number(row, "petId")).isEqualTo(db.get("pet_id"));
        assertThat(number(row, "appointmentId")).isEqualTo(db.get("appointment_id"));
        assertThat(number(row, "assigneeId")).isEqualTo(db.get("assignee_id"));
        assertThat(number(row, "orderId")).isEqualTo(db.get("order_id"));
        assertThat(row.get("serviceGroup")).isEqualTo(db.get("service_group"));
        assertThat(row.get("status")).isEqualTo(db.get("status"));
        assertThat(row.get("priorityClass")).isEqualTo(db.get("priority_class"));
        assertThat(row.get("needsReassign")).isEqualTo(db.get("needs_reassign"));
        assertThat(row.get("cancelReason")).isEqualTo(db.get("cancel_reason"));
        assertThat(row.get("petName")).isEqualTo(db.get("pet_name"));
        assertThat(row.get("species")).isEqualTo(db.get("pet_species"));
        assertThat(row.get("customerName")).isEqualTo(db.get("customer_name"));
        assertThat(row.get("assigneeName")).isEqualTo(db.get("assignee_name"));
        for (String[] pair : new String[][] {{"queueSortKey", "queue_sort_key"}, {"checkedInAt", "checked_in_at"},
                {"calledAt", "called_at"}, {"completedAt", "completed_at"}, {"cancelledAt", "cancelled_at"}}) {
            Object json = row.get(pair[0]);
            Object column = db.get(pair[1]);
            assertThat(json == null ? null : Instant.parse((String) json)).as(pair[0])
                    .isEqualTo(column == null ? null : ((Timestamp) column).toInstant());
        }
    }

    private Map<String, Long> xmins(long... visitIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (long id : visitIds) {
            result.put("visits#" + id, xmin("SELECT xmin::text::bigint FROM visits WHERE id = ?", id));
            result.put("orders#" + id, xmin("SELECT xmin::text::bigint FROM orders WHERE visit_id = ?", id));
        }
        result.put("pets", xmin("SELECT xmin::text::bigint FROM pets WHERE id = ?", pet));
        result.put("customers", xmin("SELECT xmin::text::bigint FROM customers WHERE id = ?", customer));
        result.put("staff", xmin("SELECT xmin::text::bigint FROM staff_profiles WHERE account_id = ?", vet));
        return result;
    }

    private long xmin(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private long maxId(String table) {
        return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM " + table, Long.class);
    }

    /** Lệnh ghi tự commit trên connection riêng (ngoài transaction của request), khuôn {@code AuditLogViewIT}. */
    private void autocommit(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Một lượt + Order tự commit trên connection riêng; trả id lượt. */
    private long autocommitVisit() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            long id;
            try (ResultSet rs = statement.executeQuery("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                            queue_sort_key, checked_in_by, checked_in_at)
                    VALUES ('IT05S-%s', %d, %d, %d, 'MEDICAL', 'WAITING', 'WALK_IN', '%s', %d, '%s') RETURNING id"""
                    .formatted(UUID.randomUUID().toString().substring(0, 12), branch, customer, pet, T0, receptionist,
                            T0))) {
                rs.next();
                id = rs.getLong(1);
            }
            statement.executeUpdate("""
                    INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                    VALUES ('OIT05S-%s', 'VISIT', %d, %d, %d, 'OPEN', %d)"""
                    .formatted(UUID.randomUUID().toString().substring(0, 12), customer, branch, id, receptionist));
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long activeBranch() {
        long id = data.branch("ACTIVE");
        branches.add(id);
        data.openingHours(id, LocalDate.of(2000, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalTime.of(19, 0));
        return id;
    }

    // ================================================================ HTTP

    private ResponseEntity<Map<String, Object>> get(String bearer, String path) {
        return exchange(HttpMethod.GET, bearer, path, null);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String bearer, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), method, new HttpEntity<>(body, headers), MAP);
    }

    private Map<String, Object> detail(String bearer, long id) {
        ResponseEntity<Map<String, Object>> response = get(bearer, "/api/visits/" + id);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> visit = (Map<String, Object>) response.getBody().get("data");
        return visit;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        Object data = response.getBody().get("data");
        assertThat(data).as("data luôn là mảng").isInstanceOf(List.class);
        return (List<Map<String, Object>>) data;
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(VisitQueueIT::id).toList();
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("visitId")).longValue();
    }

    private static Long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String code,
            String message) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("errorCode", code);
        if (message != null) {
            assertThat(response.getBody()).containsEntry("message", message);
        }
    }

    private static void assertAllowed(ResponseEntity<Map<String, Object>> response, boolean allowed, String what) {
        if (allowed) {
            assertThat(response.getStatusCode()).as(what + " " + response.getBody()).isEqualTo(HttpStatus.OK);
        } else {
            assertThat(response.getStatusCode()).as(what).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).as(what).containsEntry("errorCode", "ACCESS_DENIED");
        }
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "VisitQueueIT")).accessToken();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(VN).toInstant();
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
}
