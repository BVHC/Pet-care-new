package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * care-v1 #1–4 qua HTTP trên Postgres 17 thật (T39 phần 1, UC87, BR-TB-05; docs/adr/0080). Mỗi ô của ma trận lỗi
 * (thứ tự 401 → BR-TK-17 → 400 hình thức → 403 role → 404 → 403 chi nhánh → 400 BR-TB-05 → 409), ghép dữ liệu bốn loại
 * task, cột nào được ghi và cột nào không, hai chiều race với câu hủy hệ thống, và lưới an toàn khi DB từ chối lúc ghi.
 * Cuối mỗi ca bất biến cột theo trạng thái (D049) phải đúng. Đồng hồ {@link MutableClock} ở <b>năm 2019</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CareTaskDeskIT.TestBeans.class})
class CareTaskDeskIT {

    private static final Instant T0 = Instant.parse("2019-06-12T03:00:00Z");
    /** Có phần nano để chứng minh {@code handled_at} được cắt về micro giây trước khi ghi và trả về. */
    private static final Instant NOW = T0.plusNanos(123_456_789);
    private static final Instant NOW_MICROS = T0.plusNanos(123_456_000);
    private static final String PATH = "/api/care-tasks";
    private static final String COMPLETE_BODY = "{'result':'REACHED','note':'Khách hẹn tuần sau'}";
    private static final String CANCEL_BODY = "{'reason':'Khách đã chuyển đi'}";
    private static final List<String> KEYS = List.of("careTaskId", "type", "status", "branchId", "customerId",
            "customerName", "phoneToCall", "petId", "petName", "vaccinationId", "medicalRecordVisitId",
            "boardingBookingId", "dueDate", "context", "result", "note", "cancelReason", "handledBy", "handledAt",
            "createdAt");
    private static final List<String> PAGE_KEYS = List.of("content", "page", "size", "totalElements", "totalPages");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final AtomicInteger SEQ = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
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
    private CareTaskApi careTaskApi;

    private TransactionTemplate tx;
    private CareTaskItSupport support;
    private String tag;
    private long branchA;
    private long branchB;
    private long customer;
    private long pet;
    private long desk;
    private String deskToken;
    private int bookingOffset;
    private final List<Long> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        tx = new TransactionTemplate(transactionManager);
        support = new CareTaskItSupport(jdbc, transactionManager);
        tag = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        branchA = support.branch();
        branchB = support.branch();
        customer = support.customer();
        pet = support.pet(customer);
        desk = account("RECEPTIONIST", branchA);
        deskToken = token(desk);
        bookingOffset = 0;
    }

    @AfterEach
    void tearDown() {
        try {
            assertThat(support.invariantViolations()).as("bất biến cột theo trạng thái (D049)").isEmpty();
        } finally {
            // Task trỏ tới tài khoản lễ tân (handled_by) và chi nhánh: xóa task trước, rồi tài khoản, rồi mới chi nhánh.
            jdbc.update("DELETE FROM care_tasks WHERE branch_id IN (?, ?)", branchA, branchB);
            for (Long id : accounts) {
                jdbc.update("DELETE FROM sessions WHERE account_id = ?", id);
                jdbc.update("DELETE FROM staff_profiles WHERE account_id = ?", id);
                jdbc.update("DELETE FROM accounts WHERE id = ?", id);
            }
            accounts.clear();
            support.cleanUp();
        }
    }

    // ================================================================ #1 danh sách

    @Test
    void listShowsOnlyMyBranchOpenTasksSortedByDueDateThenIdWithEveryContractField() {
        long later = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 10));
        long earlyFollowUp = followUpTask("OPEN", LocalDate.of(2019, 7, 1));
        long earlyVaccine = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        vaccineTask("VACCINE_DUE", "DONE", LocalDate.of(2019, 6, 1));
        support.task("VACCINE_DUE", support.vaccination(pet), branchB, customer, pet, "OPEN", LocalDate.of(2019, 6, 1));

        Map<String, Object> data = ok(get(deskToken, ""));

        assertThat(data).containsOnlyKeys(PAGE_KEYS);
        assertThat(ids(data)).containsExactly(earlyFollowUp, earlyVaccine, later);
        assertThat(data).containsEntry("page", 0).containsEntry("size", 20).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 1);
        Map<String, Object> first = items(data).get(0);
        assertThat(first).containsOnlyKeys(KEYS).containsEntry("type", "FOLLOW_UP_DUE")
                .containsEntry("status", "OPEN").containsEntry("dueDate", "2019-07-01")
                .containsEntry("branchId", (int) branchA).containsEntry("customerId", (int) customer)
                .containsEntry("petId", (int) pet).containsEntry("petName", "Milu")
                .containsEntry("customerName", customerName()).containsEntry("phoneToCall", "0912345678")
                .containsEntry("result", null).containsEntry("note", null).containsEntry("cancelReason", null)
                .containsEntry("handledBy", null).containsEntry("handledAt", null);
        assertThat(first.get("medicalRecordVisitId")).isNotNull();
        assertThat(first.get("vaccinationId")).isNull();
        assertThat(first.get("boardingBookingId")).isNull();
        assertThat(Instant.parse((String) first.get("createdAt"))).isNotNull();
    }

    @Test
    void statusTypeAndDueDateFiltersCombineAndAReversedRangeIsAnEmptyPage() {
        long openVaccine = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 5));
        long done = vaccineTask("VACCINE_DUE", "DONE", LocalDate.of(2019, 7, 5));
        long cancelled = followUpTask("CANCELLED", LocalDate.of(2019, 7, 6));
        long openFollowUp = followUpTask("OPEN", LocalDate.of(2019, 7, 20));

        assertThat(ids(ok(get(deskToken, "?status=DONE")))).containsExactly(done);
        assertThat(ids(ok(get(deskToken, "?status=CANCELLED")))).containsExactly(cancelled);
        assertThat(ids(ok(get(deskToken, "?type=FOLLOW_UP_DUE")))).containsExactly(openFollowUp);
        assertThat(ids(ok(get(deskToken, "?dueFrom=2019-07-05&dueTo=2019-07-05")))).containsExactly(openVaccine);
        assertThat(ids(ok(get(deskToken, "?dueFrom=2019-07-06")))).containsExactly(openFollowUp);
        assertThat(ids(ok(get(deskToken, "?dueTo=2019-07-05")))).containsExactly(openVaccine);
        assertThat(ids(ok(get(deskToken, "?status=OPEN&type=VACCINE_DUE&dueFrom=2019-07-01&dueTo=2019-07-31"))))
                .containsExactly(openVaccine);
        Map<String, Object> reversed = ok(get(deskToken, "?dueFrom=2019-08-01&dueTo=2019-07-01"));
        assertThat(items(reversed)).isEmpty();
        assertThat(reversed).containsEntry("totalElements", 0);
    }

    @Test
    void pagingAndClampingFollowTheNotificationListConvention() {
        long a = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        long b = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 2));
        long c = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 3));

        Map<String, Object> second = ok(get(deskToken, "?page=1&size=2"));
        assertThat(ids(second)).containsExactly(c);
        assertThat(second).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 2);
        assertThat(ok(get(deskToken, "?size=1000"))).containsEntry("size", 100);
        assertThat(ok(get(deskToken, "?size=0"))).containsEntry("size", 1);
        Map<String, Object> negative = ok(get(deskToken, "?page=-3&size=-1"));
        assertThat(negative).containsEntry("page", 0).containsEntry("size", 1);
        assertThat(ids(negative)).containsExactly(a);
        assertThat(ids(ok(get(deskToken, "?page=9")))).isEmpty();
        assertThat(ids(ok(get(deskToken, "")))).containsExactly(a, b, c);
    }

    @Test
    void aBranchIdInTheQueryIsIgnoredBecauseTheBranchComesFromTheReceptionist() {
        long mine = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        support.task("VACCINE_DUE", support.vaccination(pet), branchB, customer, pet, "OPEN", LocalDate.of(2019, 7, 1));

        assertThat(ids(ok(get(deskToken, "?branchId=" + branchB)))).containsExactly(mine);
    }

    @Test
    void anEmptyDeskIsAnEmptyArrayNotNull() {
        Map<String, Object> data = ok(get(deskToken, ""));

        assertThat(data.get("content")).isEqualTo(List.of());
        assertThat(data).containsEntry("totalElements", 0).containsEntry("totalPages", 0);
    }

    // ================================================================ ghép dữ liệu theo loại (G3, G16)

    @Test
    void everyTypeShowsTheRightContextPhoneAndSourceId() {
        long dogType = support.vaccineType("Dại IT");
        String dogName = jdbc.queryForObject("SELECT name FROM vaccine_types WHERE id = ?", String.class, dogType);
        long vaccinationA = support.vaccination(pet, dogType);
        long due = support.task("VACCINE_DUE", vaccinationA, branchA, customer, pet, "OPEN", LocalDate.of(2019, 7, 1));
        long vaccinationB = support.vaccination(pet, dogType);
        long overdue = support.task("VACCINE_OVERDUE", vaccinationB, branchA, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 2));
        long unknownType = support.task("VACCINE_DUE", support.vaccination(pet), branchA, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 3));
        long followUp = followUpTask("OPEN", LocalDate.of(2019, 7, 5));
        long bookingReceived = booking();
        support.checkIn(bookingReceived, "0988777666");
        long pickup = support.task("PICKUP_OVERDUE", bookingReceived, branchA, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 6));
        long bookingNotReceived = booking();
        long pickupNoPhone = support.task("PICKUP_OVERDUE", bookingNotReceived, branchA, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 7));

        Map<Long, Map<String, Object>> byId = new java.util.HashMap<>();
        items(ok(get(deskToken, "?size=100"))).forEach(item -> byId.put(((Number) item.get("careTaskId")).longValue(),
                item));

        assertThat(byId).containsOnlyKeys(due, overdue, unknownType, followUp, pickup, pickupNoPhone);
        assertThat(byId.get(due)).containsEntry("context", "Tái chủng " + dogName)
                .containsEntry("phoneToCall", "0912345678").containsEntry("vaccinationId", (int) vaccinationA)
                .containsEntry("medicalRecordVisitId", null).containsEntry("boardingBookingId", null);
        assertThat(byId.get(overdue)).containsEntry("context", "Quá hạn tái chủng " + dogName)
                .containsEntry("vaccinationId", (int) vaccinationB);
        assertThat(byId.get(unknownType)).containsEntry("context", "Tái chủng");
        assertThat(byId.get(followUp)).containsEntry("context", "Tái khám ngày 05/07/2019")
                .containsEntry("phoneToCall", "0912345678").containsEntry("vaccinationId", null);
        assertThat(byId.get(pickup)).containsEntry("context", "Quá hạn đón thú — đặt chỗ #" + bookingReceived)
                .containsEntry("phoneToCall", "0988777666").containsEntry("boardingBookingId", (int) bookingReceived)
                .containsEntry("vaccinationId", null);
        assertThat(byId.get(pickupNoPhone)).containsEntry("phoneToCall", null);
    }

    @Test
    void theCustomerOfATaskIsTheOneStoredOnTheTaskNotThePetsCurrentOwner() {
        long newOwner = support.customer();
        long task = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner, pet);
        try {
            Map<String, Object> data = ok(get(deskToken, "/" + task));

            assertThat(data).containsEntry("customerId", (int) customer).containsEntry("customerName", customerName());
        } finally {
            jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", customer, pet);
        }
    }

    // ================================================================ #2 chi tiết

    @Test
    void getReturnsTheTaskAndAnotherBranchIs403AndAMissingOneIs404() {
        long mine = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        long foreign = support.task("VACCINE_DUE", support.vaccination(pet), branchB, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 1));
        long missing = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000000 FROM care_tasks", Long.class);

        Map<String, Object> data = ok(get(deskToken, "/" + mine));

        assertThat(data).containsOnlyKeys(KEYS).containsEntry("careTaskId", (int) mine);
        assertError(get(deskToken, "/" + foreign), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertError(get(deskToken, "/" + missing), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy việc chăm sóc khách #" + missing);
    }

    @Test
    void getDoesNotChangeTheTask() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        ok(get(deskToken, "/" + id));
        ok(get(deskToken, ""));

        assertThat(row(id)).isEqualTo(before);
    }

    // ================================================================ #3 complete

    @Test
    void completeWritesExactlyTheDoneColumnsFromTheClockAndTheTokenAndReturnsThem() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        ResponseEntity<Map<String, Object>> response = post(deskToken, "/" + id + "/complete",
                "{'result':'REACHED','note':'  Khách hẹn tuần sau  '}");

        Map<String, Object> data = ok(response);
        assertThat(data).containsOnlyKeys(KEYS).containsEntry("status", "DONE").containsEntry("result", "REACHED")
                .containsEntry("note", "Khách hẹn tuần sau").containsEntry("cancelReason", null)
                .containsEntry("handledBy", (int) desk);
        assertThat(Instant.parse((String) data.get("handledAt"))).isEqualTo(NOW_MICROS);
        Map<String, Object> after = row(id);
        assertThat(after).containsEntry("status", "DONE").containsEntry("result", "REACHED")
                .containsEntry("note", "Khách hẹn tuần sau").containsEntry("cancel_reason", null)
                .containsEntry("handled_by", desk).containsEntry("customer_id", before.get("customer_id"));
        assertThat(((Timestamp) after.get("handled_at")).toInstant()).isEqualTo(NOW_MICROS);
        assertThat(((Timestamp) after.get("updated_at")).toInstant()).isAfterOrEqualTo(NOW_MICROS.minusSeconds(1));
        assertThat(support.countTasks()).as("không sinh task gọi lại").isEqualTo(1);
    }

    @Test
    void unreachableIsAlsoDoneAndSpawnsNoFollowUpTask() {
        long id = vaccineTask("VACCINE_OVERDUE", "OPEN", LocalDate.of(2019, 7, 1));

        Map<String, Object> data = ok(post(deskToken, "/" + id + "/complete",
                "{'result':'UNREACHABLE','note':'Không bắt máy'}"));

        assertThat(data).containsEntry("status", "DONE").containsEntry("result", "UNREACHABLE");
        assertThat(support.countTasks()).isEqualTo(1);
    }

    @Test
    void completeWithoutAResultOrWithABlankNoteIsBrTb05AndChangesNothing() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        for (String body : List.of("{}", "{'note':'x'}", "{'result':'REACHED'}", "{'result':'REACHED','note':null}",
                "{'result':'REACHED','note':''}", "{'result':'REACHED','note':'    '}",
                "{'result':null,'note':null}")) {
            assertError(post(deskToken, "/" + id + "/complete", body), HttpStatus.BAD_REQUEST,
                    "BUSINESS_RULE_VIOLATION", "Vui lòng chọn kết quả và nhập ghi chú (BR-TB-05)");
        }

        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void completeTwiceIs409AndTheFirstOutcomeSurvives() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        ok(post(deskToken, "/" + id + "/complete", "{'result':'REACHED','note':'lần một'}"));
        Map<String, Object> afterFirst = row(id);

        ResponseEntity<Map<String, Object>> second = post(deskToken, "/" + id + "/complete",
                "{'result':'UNREACHABLE','note':'lần hai'}");

        assertError(second, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Không thể chuyển trạng thái việc chăm sóc khách từ DONE sang DONE");
        assertThat(row(id)).isEqualTo(afterFirst);
    }

    @Test
    void unknownFieldsInTheBodyAreIgnoredSoTheHandlerCannotBeForged() {
        long other = account("RECEPTIONIST", branchA);
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));

        ok(post(deskToken, "/" + id + "/complete",
                "{'result':'REACHED','note':'x','handledBy':" + other + ",'status':'CANCELLED','customerId':1}"));

        assertThat(row(id)).containsEntry("handled_by", desk).containsEntry("status", "DONE");
    }

    // ================================================================ #4 cancel

    @Test
    void cancelWritesExactlyTheCancelledColumnsAndLeavesResultAndNoteNull() {
        long id = followUpTask("OPEN", LocalDate.of(2019, 7, 1));

        Map<String, Object> data = ok(post(deskToken, "/" + id + "/cancel", "{'reason':'  Khách đã chuyển đi  '}"));

        assertThat(data).containsOnlyKeys(KEYS).containsEntry("status", "CANCELLED")
                .containsEntry("cancelReason", "Khách đã chuyển đi").containsEntry("handledBy", (int) desk)
                .containsEntry("result", null).containsEntry("note", null);
        assertThat(Instant.parse((String) data.get("handledAt"))).isEqualTo(NOW_MICROS);
        Map<String, Object> after = row(id);
        assertThat(after).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "Khách đã chuyển đi")
                .containsEntry("handled_by", desk).containsEntry("result", null).containsEntry("note", null);
        assertThat(((Timestamp) after.get("handled_at")).toInstant()).isEqualTo(NOW_MICROS);
    }

    @Test
    void cancelWithABlankReasonIsBrTb05AndAReasonOverTheColumnLengthIsValidationFailed() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        for (String body : List.of("{}", "{'reason':null}", "{'reason':''}", "{'reason':'   '}")) {
            assertError(post(deskToken, "/" + id + "/cancel", body), HttpStatus.BAD_REQUEST,
                    "BUSINESS_RULE_VIOLATION", "Vui lòng nhập lý do hủy (BR-TB-05)");
        }
        assertError(post(deskToken, "/" + id + "/cancel", "{'reason':'" + "a".repeat(301) + "'}"),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "reason: Lý do tối đa 300 ký tự");

        assertThat(row(id)).isEqualTo(before);
        ok(post(deskToken, "/" + id + "/cancel", "{'reason':'" + "a".repeat(300) + "'}"));
        assertThat(row(id)).containsEntry("status", "CANCELLED");
    }

    @Test
    void aNoteOverTheLimitIsValidationFailedAndTheLimitItselfIsAccepted() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        assertError(post(deskToken, "/" + id + "/complete", "{'result':'REACHED','note':'" + "a".repeat(2001) + "'}"),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "note: Ghi chú tối đa 2000 ký tự");

        assertThat(row(id)).isEqualTo(before);
        ok(post(deskToken, "/" + id + "/complete", "{'result':'REACHED','note':'" + "a".repeat(2000) + "'}"));
    }

    @Test
    void cancellingADoneOrCancelledTaskIs409AndKeepsItsData() {
        long done = vaccineTask("VACCINE_DUE", "DONE", LocalDate.of(2019, 7, 1));
        long cancelled = vaccineTask("VACCINE_DUE", "CANCELLED", LocalDate.of(2019, 7, 1));
        Map<String, Object> doneBefore = row(done);
        Map<String, Object> cancelledBefore = row(cancelled);

        assertError(post(deskToken, "/" + done + "/cancel", CANCEL_BODY), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION", "Không thể chuyển trạng thái việc chăm sóc khách từ DONE sang CANCELLED");
        assertError(post(deskToken, "/" + cancelled + "/cancel", CANCEL_BODY), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION", "Không thể chuyển trạng thái việc chăm sóc khách từ CANCELLED sang CANCELLED");
        assertError(post(deskToken, "/" + cancelled + "/complete", COMPLETE_BODY), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION", "Không thể chuyển trạng thái việc chăm sóc khách từ CANCELLED sang DONE");

        assertThat(row(done)).isEqualTo(doneBefore);
        assertThat(row(cancelled)).isEqualTo(cancelledBefore);
    }

    // ================================================================ lỗi hình thức (G12)

    @Test
    void malformedInputIs400MalformedRequestWithTheParameterNamed() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        assertError(get(deskToken, "/abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'careTaskId' sai kiểu dữ liệu");
        assertError(post(deskToken, "/abc/complete", COMPLETE_BODY), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'careTaskId' sai kiểu dữ liệu");
        assertError(get(deskToken, "?status=MAYBE"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'status' sai kiểu dữ liệu");
        assertError(get(deskToken, "?type=foo"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'type' sai kiểu dữ liệu");
        assertError(get(deskToken, "?dueFrom=2019-13-45"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'dueFrom' sai kiểu dữ liệu");
        assertError(get(deskToken, "?dueTo=01/07/2019"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'dueTo' sai kiểu dữ liệu");
        assertError(get(deskToken, "?page=abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'page' sai kiểu dữ liệu");
        assertError(post(deskToken, "/" + id + "/complete", "{'result':'MAYBE','note':'x'}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", "Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu");
        assertError(post(deskToken, "/" + id + "/complete", "{'result':'REACHED','note':"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertError(post(deskToken, "/" + id + "/cancel", null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);

        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void aNonJsonContentTypeIs415() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        HttpHeaders headers = bearer(deskToken);
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<Map<String, Object>> response = http.exchange(uri("/" + id + "/complete"), HttpMethod.POST,
                new HttpEntity<>("result=REACHED", headers), MAP);

        assertError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", null);
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void aNumberWhereTheNoteStringIsExpectedIsCoercedByJacksonLikeEveryOtherEndpoint() {
        // Jackson ép số thành chuỗi cho trường String (không bật STRICT cho scalar); ghi lại hành vi để không bất ngờ.
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));

        Map<String, Object> data = ok(post(deskToken, "/" + id + "/complete", "{'result':'REACHED','note':12}"));

        assertThat(data).containsEntry("note", "12");
    }

    // ================================================================ xác thực, phân quyền (G1)

    @Test
    void withoutAValidTokenEveryEndpointIs401EvenWithAnInvalidBody() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));

        for (String token : new String[] {null, "garbage.token.value"}) {
            assertThat(get(token, "").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(token, "/" + id).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post(token, "/" + id + "/complete", "{}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post(token, "/" + id + "/cancel", "not json").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void everyRoleExceptTheReceptionistIs403OnAllFourEndpointsAndChangesNothing() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);

        for (String role : List.of("VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER")) {
            String token = token(account(role, branchA));
            assertError(get(token, ""), HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
            assertError(get(token, "/" + id), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
            assertError(post(token, "/" + id + "/complete", COMPLETE_BODY), HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    null);
            assertError(post(token, "/" + id + "/cancel", CANCEL_BODY), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        }

        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void aReceptionistWhoMustChangeThePasswordIsBlockedBeforeAnythingElse() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        long fresh = account("RECEPTIONIST", branchA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", fresh);
        String token = token(fresh);

        assertError(get(token, ""), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        ResponseEntity<Map<String, Object>> blocked = post(token, "/" + id + "/complete", "{'result':'MAYBE'}");

        assertError(blocked, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        assertThat((String) blocked.getBody().get("message")).endsWith("(BR-TK-17)");
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void theBranchComesFromTheDatabaseOnEveryRequestSoATransferTakesEffectWithTheSameToken() {
        long task = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        assertThat(ids(ok(get(deskToken, "")))).containsExactly(task);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, desk);

        assertThat(ids(ok(get(deskToken, "")))).isEmpty();
        assertError(get(deskToken, "/" + task), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertError(post(deskToken, "/" + task + "/complete", COMPLETE_BODY), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertThat(row(task)).containsEntry("status", "OPEN");
    }

    // ================================================================ thứ tự lỗi (G12)

    @Test
    void validationAndMalformedBodiesComeBeforeTheRoleCheck() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        String vet = token(account("VET", branchA));

        assertError(post(vet, "/" + id + "/complete", "{'result':'REACHED','note':'" + "a".repeat(2001) + "'}"),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertError(post(vet, "/" + id + "/complete", "{'result':'MAYBE'}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        assertError(post(vet, "/" + id + "/complete", COMPLETE_BODY), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void notFoundComesBeforeTheBodyRuleAndTheBranchCheckComesBeforeIt() {
        long foreign = support.task("VACCINE_DUE", support.vaccination(pet), branchB, customer, pet, "OPEN",
                LocalDate.of(2019, 7, 1));
        long missing = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000000 FROM care_tasks", Long.class);

        assertError(post(deskToken, "/" + missing + "/complete", "{}"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy việc chăm sóc khách #" + missing);
        assertError(post(deskToken, "/" + missing + "/cancel", "{}"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                null);
        assertError(post(deskToken, "/" + foreign + "/complete", "{}"), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertError(post(deskToken, "/" + foreign + "/cancel", "{}"), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertThat(row(foreign)).containsEntry("status", "OPEN");
    }

    @Test
    void theBodyRuleComesBeforeTheStateConflict() {
        long done = vaccineTask("VACCINE_DUE", "DONE", LocalDate.of(2019, 7, 1));
        long cancelled = vaccineTask("VACCINE_DUE", "CANCELLED", LocalDate.of(2019, 7, 1));

        assertError(post(deskToken, "/" + done + "/complete", "{'result':'REACHED','note':' '}"),
                HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "Vui lòng chọn kết quả và nhập ghi chú (BR-TB-05)");
        assertError(post(deskToken, "/" + cancelled + "/cancel", "{'reason':' '}"), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "Vui lòng nhập lý do hủy (BR-TB-05)");
    }

    // ================================================================ race với câu hủy hệ thống (G7)

    @Test
    void completeWaitsForASystemCancelHoldingTheRowThenAnswers409AndKeepsTheSystemCancel() throws Exception {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> system = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.update("""
                        UPDATE care_tasks SET status = 'CANCELLED', cancel_reason = 'Đã tiêm lại (BR-TB-03)',
                                              handled_at = now(), updated_at = now()
                        WHERE id = ? AND status = 'OPEN'""", id);
                held.countDown();
                await(release);
            }));
            await(held);
            Future<ResponseEntity<Map<String, Object>>> request = pool
                    .submit(() -> post(deskToken, "/" + id + "/complete", COMPLETE_BODY));
            awaitRowLockWaiter();

            release.countDown();
            system.get(30, TimeUnit.SECONDS);

            assertError(request.get(30, TimeUnit.SECONDS), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "Không thể chuyển trạng thái việc chăm sóc khách từ CANCELLED sang DONE");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("result", null)
                .containsEntry("note", null).containsEntry("handled_by", null)
                .containsEntry("cancel_reason", "Đã tiêm lại (BR-TB-03)");
    }

    @Test
    void completeAndTheSystemCancelRacingLeaveExactlyOneConsistentOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int completed = 0;
        int cancelledBySystem = 0;
        try {
            for (int i = 0; i < 8; i++) {
                long vaccination = support.vaccination(pet);
                long id = support.task("VACCINE_DUE", vaccination, branchA, customer, pet, "OPEN",
                        LocalDate.of(2019, 7, 1));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<ResponseEntity<Map<String, Object>>> request = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return post(deskToken, "/" + id + "/complete", COMPLETE_BODY);
                });
                Future<?> system = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    tx.executeWithoutResult(status -> careTaskApi.cancelForRevaccination(List.of(vaccination)));
                    return null;
                });
                system.get(30, TimeUnit.SECONDS);
                ResponseEntity<Map<String, Object>> response = request.get(30, TimeUnit.SECONDS);

                Map<String, Object> after = row(id);
                if (response.getStatusCode() == HttpStatus.OK) {
                    completed++;
                    assertThat(after).containsEntry("status", "DONE").containsEntry("handled_by", desk)
                            .containsEntry("cancel_reason", null);
                } else {
                    cancelledBySystem++;
                    assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", null);
                    assertThat(after).containsEntry("status", "CANCELLED").containsEntry("result", null)
                            .containsEntry("handled_by", null);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(completed + cancelledBySystem).isEqualTo(8);
    }

    // ================================================================ DB từ chối lúc ghi (lưới an toàn)

    @Test
    void aDatabaseErrorWhileWritingIsAnswered409AndTheTaskStaysOpen() {
        long id = vaccineTask("VACCINE_DUE", "OPEN", LocalDate.of(2019, 7, 1));
        Map<String, Object> before = row(id);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_care_task_reject() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'it_care_task_reject' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER it_care_task_reject BEFORE UPDATE ON care_tasks FOR EACH ROW WHEN (OLD.id = "
                + id + ") EXECUTE FUNCTION it_care_task_reject()");
        try {
            ResponseEntity<Map<String, Object>> complete = post(deskToken, "/" + id + "/complete", COMPLETE_BODY);
            ResponseEntity<Map<String, Object>> cancel = post(deskToken, "/" + id + "/cancel", CANCEL_BODY);

            assertError(complete, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", null);
            assertError(cancel, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", null);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_care_task_reject ON care_tasks");
            jdbc.execute("DROP FUNCTION IF EXISTS it_care_task_reject()");
        }
        assertThat(row(id)).isEqualTo(before);
    }

    // ================================================================ fixtures

    private long vaccineTask(String type, String status, LocalDate due) {
        return support.task(type, support.vaccination(pet), branchA, customer, pet, status, due);
    }

    private long followUpTask(String status, LocalDate due) {
        return support.task("FOLLOW_UP_DUE", support.medicalRecord(branchA, customer, pet), branchA, customer, pet,
                status, due);
    }

    /** Mỗi thú chỉ một đặt chỗ đang hiệu lực mỗi khoảng ngày (EXCLUDE chồng ngày): dời 10 ngày mỗi lần. */
    private long booking() {
        long id = support.booking(branchA, customer, pet, bookingOffset);
        bookingOffset += 10;
        return id;
    }

    private String customerName() {
        return jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, customer);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, result, note, cancel_reason, handled_by, handled_at, customer_id, updated_at,
                       xmin::text AS xmin
                FROM care_tasks WHERE id = ?""", id);
    }

    private long account(String role, Long branch) {
        String email = "care-desk-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
        long id;
        if ("CUSTOMER".equals(role)) {
            id = jdbc.queryForObject("""
                    INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE')
                    RETURNING id""", Long.class, email);
        } else {
            id = jdbc.queryForObject("""
                    INSERT INTO accounts (email, phone, password_hash, role, status)
                    VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id""", Long.class, email, role);
            boolean branchScoped = !"ADMIN".equals(role) && !"SUPER_MANAGER".equals(role);
            jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                    role + " " + tag, branchScoped ? branch : null);
        }
        accounts.add(id);
        return id;
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "CareTaskDeskIT")).accessToken();
    }

    /** Chờ một backend đang đứng chờ khóa dòng {@code care_tasks} ({@code pg_stat_activity}). */
    private void awaitRowLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%care_tasks%' AND query ILIKE '%for no key update%'""",
                    Long.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy request nào chờ khóa dòng care_tasks");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("hết thời gian chờ latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // ================================================================ HTTP

    private ResponseEntity<Map<String, Object>> get(String token, String suffix) {
        return http.exchange(uri(suffix), HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String token, String suffix, String body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(uri(suffix), HttpMethod.POST, new HttpEntity<>(body == null ? null : json(body), headers),
                MAP);
    }

    private URI uri(String suffix) {
        return URI.create(http.getRootUri() + PATH + suffix);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    /** Viết JSON trong test bằng nháy đơn cho gọn. */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("content");
    }

    private static List<Long> ids(Map<String, Object> data) {
        return items(data).stream().map(item -> ((Number) item.get("careTaskId")).longValue()).toList();
    }

    /** {@code errorCode == null} nghĩa là không kiểm phong bì lỗi (dùng cho ca mong 200). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String errorCode,
            String message) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        if (errorCode == null) {
            return;
        }
        assertThat(response.getBody()).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp",
                        "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
        if (message != null) {
            assertThat(response.getBody()).containsEntry("message", message);
        }
    }
}
