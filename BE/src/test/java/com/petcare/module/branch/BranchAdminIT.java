package com.petcare.module.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.BookedStay;
import com.petcare.module.branch.api.BranchClinicCancellationEvent;
import com.petcare.module.branch.api.BranchClinicCancellationEvent.Cause;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.api.StaffDirectoryApi;

/**
 * UC12, UC14 (branch-v1 #1–12) và {@code BranchQueryApi} trên Postgres 17 thật qua HTTP: RBAC và phạm vi chi nhánh,
 * Chi nhánh#2 (kích hoạt, kể cả hai yêu cầu đồng thời), BR-CN-02…05, BR-LH-10, sự kiện hủy hàng loạt (listener giả
 * bắt hoặc ném lỗi để thử rollback). {@code StaffDirectoryApi}, {@code AppointmentQueryApi}, {@code BoardingQueryApi},
 * {@code PetQueryApi}, {@code CustomerQueryApi} chưa có implementation nên được thay bằng mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BranchAdminIT.TestBeans.class})
class BranchAdminIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Thay cho appointment / boarding: ghi lại sự kiện hủy hàng loạt, hoặc ném lỗi để thử rollback cả use case. */
    static class EventSink {
        final List<BranchClinicCancellationEvent> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean failNext = new AtomicBoolean();

        @EventListener
        void on(BranchClinicCancellationEvent event) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("listener failure");
            }
            events.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        EventSink eventSink() {
            return new EventSink();
        }
    }

    @MockitoBean
    private StaffDirectoryApi staff;

    @MockitoBean
    private AppointmentQueryApi appointments;

    @MockitoBean
    private BoardingQueryApi boardings;

    @MockitoBean
    private PetQueryApi pets;

    @MockitoBean
    private CustomerQueryApi customers;

    @Autowired
    private EventSink sink;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BranchQueryApi branchQuery;

    private long branchA;
    private long branchB;
    private String superManager;
    private String managerA;
    private String managerB;
    private String vetA;
    private String customer;

    @BeforeEach
    void setUp() {
        reset(staff, appointments, boardings, pets, customers);
        sink.events.clear();
        sink.failNext.set(false);
        branchA = branchRow("ACTIVE");
        branchB = branchRow("ACTIVE");
        superManager = token(staffAccount("SUPER_MANAGER", null));
        managerA = token(staffAccount("BRANCH_MANAGER", branchA));
        managerB = token(staffAccount("BRANCH_MANAGER", branchB));
        vetA = token(staffAccount("VET", branchA));
        customer = token(jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, "branch-it-c-" + UUID.randomUUID() + "@petcare.test"));
    }

    // ------------------------------------------------------------------ UC12 chi nhánh

    @Test
    void onlySuperManagerCreatesUpdatesAndActivates() {
        Map<String, Object> body = newBranchBody(unique("CN"));
        assertError(call(HttpMethod.POST, "/api/branches", managerA, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, "/api/branches", vetA, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, "/api/branches", null, body), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.PATCH, "/api/branches/" + branchA, managerA, Map.of("name", "x")),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, "/api/branches/" + branchA + "/activate", managerA, null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, "/api/branches", customer, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    void createStartsAsDraftAndRejectsBadInput() {
        String name = unique("CN Quận 1");
        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/branches", superManager,
                newBranchBody(name));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(created)).containsEntry("name", name).containsEntry("status", "DRAFT")
                .containsEntry("acceptsAfterHoursEmergency", false).containsKey("branchId")
                .containsEntry("activatedAt", null).doesNotContainKey("id");

        assertRule(call(HttpMethod.POST, "/api/branches", superManager, newBranchBody(name)), "BR-CN-01");
        Map<String, Object> noPhone = newBranchBody(unique("CN"));
        noPhone.remove("phone");
        assertError(call(HttpMethod.POST, "/api/branches", superManager, noPhone), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        Map<String, Object> badLatitude = newBranchBody(unique("CN"));
        badLatitude.put("latitude", 91);
        assertError(call(HttpMethod.POST, "/api/branches", superManager, badLatitude), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
    }

    @Test
    void superManagerSetsTheEmergencyFlag() {
        long id = ((Number) data(call(HttpMethod.POST, "/api/branches", superManager, newBranchBody(unique("CN"))))
                .get("branchId")).longValue();

        ResponseEntity<Map<String, Object>> updated = call(HttpMethod.PATCH, "/api/branches/" + id, superManager,
                Map.of("acceptsAfterHoursEmergency", true, "phone", "0289999999"));

        assertThat(data(updated)).containsEntry("acceptsAfterHoursEmergency", true).containsEntry("phone", "0289999999");
        assertThat(jdbc.queryForObject("SELECT accepts_after_hours_emergency FROM branches WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertError(call(HttpMethod.PATCH, "/api/branches/999999999", superManager, Map.of("name", "x")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void branchStaffSeeOnlyTheirOwnBranch() {
        List<Long> managerView = idsOf(dataList(call(HttpMethod.GET, "/api/branches", managerA, null)), "branchId");
        List<Long> chainView = idsOf(dataList(call(HttpMethod.GET, "/api/branches", superManager, null)), "branchId");

        assertThat(managerView).containsExactly(branchA);
        assertThat(chainView).contains(branchA, branchB);
        assertThat(call(HttpMethod.GET, "/api/branches/" + branchA, vetA, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertError(call(HttpMethod.GET, "/api/branches/" + branchB, vetA, null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void activationNeedsAManagerAndOpeningHours() {
        long draft = branchRow("DRAFT");
        String path = "/api/branches/" + draft + "/activate";

        when(staff.countBranchManagers(draft)).thenReturn(0);
        assertRule(call(HttpMethod.POST, path, superManager, null), "BR-QT-04");

        when(staff.countBranchManagers(draft)).thenReturn(1);
        assertRule(call(HttpMethod.POST, path, superManager, null), "BR-CN-01");
        assertThat(statusOf(draft)).isEqualTo("DRAFT");

        hoursRows(draft, LocalDate.now(ZONE), true);
        ResponseEntity<Map<String, Object>> activated = call(HttpMethod.POST, path, superManager, null);
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(activated)).containsEntry("status", "ACTIVE");
        assertThat(data(activated).get("activatedAt")).isNotNull();
        assertThat(statusOf(draft)).isEqualTo("ACTIVE");

        assertError(call(HttpMethod.POST, path, superManager, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertError(call(HttpMethod.POST, "/api/branches/999999999/activate", superManager, null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void twoConcurrentActivationsHaveExactlyOneWinner() throws Exception {
        long draft = branchRow("DRAFT");
        hoursRows(draft, LocalDate.now(ZONE), true);
        when(staff.countBranchManagers(draft)).thenReturn(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return HttpStatus.valueOf(call(HttpMethod.POST, "/api/branches/" + draft + "/activate",
                            superManager, null).getStatusCode().value());
                }));
            }
            go.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ UC14 giờ mở cửa

    @Test
    void openingHoursAreWrittenByTheBranchManagerOfThatBranchOnly() {
        long draft = branchRow("DRAFT");
        long manager = staffAccount("BRANCH_MANAGER", draft);
        String path = "/api/branches/" + draft + "/opening-hours";
        Map<String, Object> body = hoursBody(LocalDate.now(ZONE), true, false);

        assertError(call(HttpMethod.PUT, path, managerB, body), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.PUT, path, superManager, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.PUT, path, vetA, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        ResponseEntity<Map<String, Object>> saved = call(HttpMethod.PUT, path, token(manager), body);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM opening_hours WHERE branch_id = ?", Integer.class,
                draft)).isEqualTo(7);
    }

    @Test
    void openingHoursAreReturnedAsHHmmWithAllSevenDays() {
        long draft = branchRow("DRAFT");
        long manager = staffAccount("BRANCH_MANAGER", draft);
        String managerToken = token(manager);
        call(HttpMethod.PUT, "/api/branches/" + draft + "/opening-hours", managerToken,
                hoursBody(LocalDate.now(ZONE), true, false));

        List<Map<String, Object>> versions = dataList(call(HttpMethod.GET,
                "/api/branches/" + draft + "/opening-hours", managerToken, null));

        assertThat(versions).hasSize(1);
        List<?> days = (List<?>) versions.get(0).get("days");
        assertThat(days).hasSize(7);
        Map<?, ?> monday = (Map<?, ?>) days.get(0);
        assertThat(monday.get("dayOfWeek")).isEqualTo(1);
        assertThat(((List<?>) monday.get("ranges"))).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) monday.get("ranges")).get(0)).get("open")).isEqualTo("08:00");
        assertThat(((Map<?, ?>) ((List<?>) monday.get("ranges")).get(1)).get("close")).isEqualTo("19:00");
        assertThat(((List<?>) ((Map<?, ?>) days.get(6)).get("ranges"))).isEmpty();
    }

    @Test
    void invalidHoursAreRejected_BR_CN_02() {
        long draft = branchRow("DRAFT");
        String managerToken = token(staffAccount("BRANCH_MANAGER", draft));
        String path = "/api/branches/" + draft + "/opening-hours";
        LocalDate today = LocalDate.now(ZONE);

        Map<String, Object> overlapping = hoursBody(today, false, false);
        overrideMonday(overlapping, range("08:00", "13:00"), range("12:00", "19:00"));
        assertRule(call(HttpMethod.PUT, path, managerToken, overlapping), "BR-CN-02");

        Map<String, Object> inverted = hoursBody(today, false, false);
        overrideMonday(inverted, range("12:00", "08:00"));
        assertRule(call(HttpMethod.PUT, path, managerToken, inverted), "BR-CN-02");

        Map<String, Object> threeRanges = hoursBody(today, false, false);
        overrideMonday(threeRanges, range("08:00", "10:00"), range("11:00", "13:00"), range("14:00", "16:00"));
        assertRule(call(HttpMethod.PUT, path, managerToken, threeRanges), "BR-CN-02");

        Map<String, Object> sixDays = hoursBody(today, false, false);
        ((List<?>) sixDays.get("days")).remove(6);
        assertRule(call(HttpMethod.PUT, path, managerToken, sixDays), "BR-CN-02");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM opening_hours WHERE branch_id = ?", Integer.class,
                draft)).isZero();
    }

    @Test
    void effectiveDateMustBeTomorrowOrLaterForAnActiveBranch_BR_CN_04() {
        String path = "/api/branches/" + branchA + "/opening-hours";
        LocalDate today = LocalDate.now(ZONE);

        assertRule(call(HttpMethod.PUT, path, managerA, hoursBody(today, false, false)), "BR-CN-04");
        assertThat(call(HttpMethod.PUT, path, managerA, hoursBody(today.plusDays(1), false, false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        long draft = branchRow("DRAFT");
        String draftManager = token(staffAccount("BRANCH_MANAGER", draft));
        assertRule(call(HttpMethod.PUT, "/api/branches/" + draft + "/opening-hours", draftManager,
                hoursBody(today.minusDays(1), false, false)), "BR-CN-04");
    }

    @Test
    void sameEffectiveDateOverwritesAndCurrentPlusFutureVersionsAreListed() {
        LocalDate today = LocalDate.now(ZONE);
        hoursRows(branchA, today.minusMonths(2), true);                 // bản cũ, đã bị bản sau thay
        hoursRows(branchA, today.minusDays(3), true);                   // bản đang áp dụng
        String path = "/api/branches/" + branchA + "/opening-hours";

        call(HttpMethod.PUT, path, managerA, hoursBody(today.plusDays(10), true, false));
        call(HttpMethod.PUT, path, managerA, hoursBody(today.plusDays(10), false, false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM opening_hours WHERE branch_id = ? AND effective_from = ?",
                Integer.class, branchA, today.plusDays(10))).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM opening_hours WHERE branch_id = ? AND effective_from = ? AND open_2 IS NOT NULL
                """, Integer.class, branchA, today.plusDays(10))).isZero();
        List<Map<String, Object>> versions = dataList(call(HttpMethod.GET, path, managerA, null));
        assertThat(versions).extracting(v -> v.get("effectiveFrom")).containsExactly(
                today.minusDays(3).toString(), today.plusDays(10).toString());
    }

    @Test
    void narrowingHoursIsRefusedUnlessTheManagerCancelsTheAffectedBookings_BR_CN_04() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate effective = today.plusDays(1);
        hoursRows(branchA, today.minusDays(30), true);
        LocalDate monday = effective;
        while (monday.getDayOfWeek().getValue() != 1) {
            monday = monday.plusDays(1);
        }
        BookedSlot afternoon = new BookedSlot(501L, "LH-501", branchA, 7L, 8L, monday, LocalTime.of(15, 0));
        BookedSlot morning = new BookedSlot(502L, "LH-502", branchA, 7L, 8L, monday, LocalTime.of(9, 0));
        BookedStay sundayCheckout = new BookedStay(601L, "LT-601", 7L, 8L, monday, monday.plusDays(6));
        when(appointments.findBookedFrom(branchA, effective)).thenReturn(List.of(afternoon, morning));
        when(boardings.findBookedFrom(branchA, effective)).thenReturn(List.of(sundayCheckout));
        when(pets.findPet(7L)).thenReturn(Optional.of(new PetSummary(7L, 8L, "Mực", Species.DOG, null, null, null)));
        when(customers.findContact(8L)).thenReturn(Optional.of(
                new CustomerContact(8L, "Nguyễn Văn A", "0901234567", null, null, false)));
        String path = "/api/branches/" + branchA + "/opening-hours";
        Map<String, Object> morningsOnly = hoursBody(effective, false, false);
        ((Map<String, Object>) ((List<?>) morningsOnly.get("days")).get(0)).put("ranges", List.of(range("08:00", "12:00")));
        for (int i = 1; i < 6; i++) {
            ((Map<String, Object>) ((List<?>) morningsOnly.get("days")).get(i)).put("ranges", List.of(range("08:00", "12:00")));
        }

        // Xem trước: không ghi gì, trả danh sách đủ tên, SĐT.
        Map<String, Object> impact = data(call(HttpMethod.POST, path + "/impact", managerA, morningsOnly));
        List<Map<String, Object>> affectedAppointments = (List<Map<String, Object>>) impact.get("appointments");
        assertThat(affectedAppointments).extracting(m -> ((Number) m.get("appointmentId")).longValue())
                .containsExactly(501L);
        assertThat(affectedAppointments.get(0)).containsEntry("code", "LH-501").containsEntry("slotStart", "15:00")
                .containsEntry("petName", "Mực").containsEntry("customerName", "Nguyễn Văn A")
                .containsEntry("customerPhone", "0901234567");
        assertThat((List<?>) impact.get("boardingBookings")).hasSize(1);
        assertThat(rowsAt(branchA, effective)).isZero();

        // Lưu không hủy: từ chối, DB không đổi, không có sự kiện.
        assertRule(call(HttpMethod.PUT, path, managerA, morningsOnly), "BR-CN-04");
        assertThat(rowsAt(branchA, effective)).isZero();
        assertThat(sink.events).isEmpty();

        // Lưu có hủy hàng loạt: ghi và phát sự kiện đúng id.
        morningsOnly.put("cancelAffected", true);
        assertThat(call(HttpMethod.PUT, path, managerA, morningsOnly).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsAt(branchA, effective)).isEqualTo(7);
        assertThat(sink.events).hasSize(1);
        BranchClinicCancellationEvent event = sink.events.get(0);
        assertThat(event.branchId()).isEqualTo(branchA);
        assertThat(event.cause()).isEqualTo(Cause.OPENING_HOURS_REDUCED);
        assertThat(event.appointmentIds()).containsExactly(501L);
        assertThat(event.boardingBookingIds()).containsExactly(601L);
        assertThat(event.actorId()).isNotNull();
    }

    @Test
    void listenerFailureRollsBackTheNewOpeningHours() {
        LocalDate effective = LocalDate.now(ZONE).plusDays(1);
        hoursRows(branchA, LocalDate.now(ZONE).minusDays(30), true);
        LocalDate monday = effective;
        while (monday.getDayOfWeek().getValue() != 1) {
            monday = monday.plusDays(1);
        }
        when(appointments.findBookedFrom(branchA, effective)).thenReturn(List.of(
                new BookedSlot(501L, "LH-501", branchA, 7L, 8L, monday, LocalTime.of(15, 0))));
        Map<String, Object> morningsOnly = hoursBody(effective, false, true);
        for (int i = 0; i < 6; i++) {
            ((Map<String, Object>) ((List<?>) morningsOnly.get("days")).get(i)).put("ranges", List.of(range("08:00", "12:00")));
        }
        sink.failNext.set(true);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PUT,
                "/api/branches/" + branchA + "/opening-hours", managerA, morningsOnly);

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(rowsAt(branchA, effective)).isZero();
    }

    // ------------------------------------------------------------------ UC14 ngày nghỉ

    @Test
    void holidayLifecycle_BR_CN_03() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate day = today.plusDays(20);
        String path = "/api/branches/" + branchA + "/holidays";

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, path, managerA,
                Map.of("holidayDate", day.toString(), "reason", "Giỗ Tổ"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(created)).containsEntry("holidayDate", day.toString()).containsEntry("reason", "Giỗ Tổ")
                .containsKey("holidayId");
        long holidayId = ((Number) data(created).get("holidayId")).longValue();
        assertThat(jdbc.queryForObject("SELECT created_by FROM holidays WHERE id = ?", Long.class, holidayId))
                .isNotNull();

        assertRule(call(HttpMethod.POST, path, managerA, Map.of("holidayDate", day.toString())), "BR-CN-03");
        assertRule(call(HttpMethod.POST, path, managerA, Map.of("holidayDate", today.minusDays(1).toString())),
                "BR-CN-03");
        assertError(call(HttpMethod.POST, path, managerA, Map.of("reason", "x")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");

        assertThat(idsOf(dataList(call(HttpMethod.GET, path + "?from=" + day + "&to=" + day, vetA, null)),
                "holidayId")).containsExactly(holidayId);
        assertThat(dataList(call(HttpMethod.GET, path + "?from=" + day.plusDays(1), vetA, null))).isEmpty();

        assertThat(call(HttpMethod.DELETE, path + "/" + holidayId, managerA, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE id = ?", Integer.class, holidayId))
                .isZero();
        assertError(call(HttpMethod.DELETE, path + "/" + holidayId, managerA, null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void holidayWritesAreLimitedToTheBranchManagerOfThatBranch() {
        String path = "/api/branches/" + branchA + "/holidays";
        Map<String, Object> body = Map.of("holidayDate", LocalDate.now(ZONE).plusDays(20).toString());

        assertError(call(HttpMethod.POST, path, managerB, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(call(HttpMethod.POST, path, vetA, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, path, superManager, body), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(call(HttpMethod.GET, path, superManager, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(call(HttpMethod.GET, path, managerB, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void cannotDeleteTodaysHoliday() {
        LocalDate today = LocalDate.now(ZONE);
        long holidayId = jdbc.queryForObject("""
                INSERT INTO holidays (branch_id, holiday_date, created_by) VALUES (?, ?, ?) RETURNING id
                """, Long.class, branchA, today, staffAccount("BRANCH_MANAGER", branchA));

        assertRule(call(HttpMethod.DELETE, "/api/branches/" + branchA + "/holidays/" + holidayId, managerA, null),
                "BR-CN-03");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE id = ?", Integer.class, holidayId))
                .isEqualTo(1);
    }

    @Test
    void holidayOverBookedDaysIsRefusedUnlessCancelled_BR_LH_10() {
        LocalDate day = LocalDate.now(ZONE).plusDays(20);
        when(appointments.findBookedFrom(branchA, day)).thenReturn(List.of(
                new BookedSlot(501L, "LH-501", branchA, 7L, 8L, day, LocalTime.of(9, 0)),
                new BookedSlot(502L, "LH-502", branchA, 7L, 8L, day.plusDays(1), LocalTime.of(9, 0))));
        when(boardings.findBookedFrom(branchA, day)).thenReturn(List.of(
                new BookedStay(601L, "LT-601", 7L, 8L, day.minusDays(2), day),
                new BookedStay(602L, "LT-602", 7L, 8L, day.minusDays(2), day.plusDays(2))));
        when(pets.findPet(7L)).thenReturn(Optional.of(new PetSummary(7L, 8L, "Mực", Species.DOG, null, null, null)));
        when(customers.findContact(8L)).thenReturn(Optional.empty());
        String path = "/api/branches/" + branchA + "/holidays";

        Map<String, Object> impact = data(call(HttpMethod.POST, path + "/impact", managerA,
                Map.of("holidayDate", day.toString())));
        assertThat((List<?>) impact.get("appointments")).hasSize(1);
        assertThat((List<?>) impact.get("boardingBookings")).hasSize(1);

        assertRule(call(HttpMethod.POST, path, managerA, Map.of("holidayDate", day.toString())), "BR-LH-10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ?", Integer.class, branchA))
                .isZero();

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, path, managerA,
                Map.of("holidayDate", day.toString(), "cancelAffected", true));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sink.events).hasSize(1);
        BranchClinicCancellationEvent event = sink.events.get(0);
        assertThat(event.cause()).isEqualTo(Cause.HOLIDAY_ADDED);
        assertThat(event.appointmentIds()).containsExactly(501L);
        assertThat(event.boardingBookingIds()).containsExactly(601L);
    }

    @Test
    void listenerFailureRollsBackTheHoliday() {
        LocalDate day = LocalDate.now(ZONE).plusDays(20);
        when(appointments.findBookedFrom(branchA, day)).thenReturn(List.of(
                new BookedSlot(501L, "LH-501", branchA, 7L, 8L, day, LocalTime.of(9, 0))));
        sink.failNext.set(true);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/branches/" + branchA + "/holidays",
                managerA, Map.of("holidayDate", day.toString(), "cancelAffected", true));

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ?", Integer.class, branchA))
                .isZero();
    }

    // ------------------------------------------------------------------ BranchQueryApi

    @Test
    void branchQueryApiAnswersFromRealData() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate monday = today.plusDays(8 - today.getDayOfWeek().getValue());   // thứ Hai kế tiếp
        hoursRows(branchA, today.minusDays(30), true);
        long manager = staffAccount("BRANCH_MANAGER", branchA);
        jdbc.update("INSERT INTO holidays (branch_id, holiday_date, created_by) VALUES (?, ?, ?)",
                branchA, monday.plusDays(1), manager);

        assertThat(branchQuery.findBranch(branchA)).get().extracting(BranchQueryApi.BranchSummary::status)
                .isEqualTo(BranchQueryApi.BranchStatus.ACTIVE);
        assertThat(branchQuery.findBranch(999_999_999L)).isEmpty();
        assertThat(branchQuery.listActiveBranches()).extracting(BranchQueryApi.BranchSummary::branchId)
                .contains(branchA, branchB);
        assertThat(branchQuery.openingRangesOn(branchA, monday)).hasSize(2);
        assertThat(branchQuery.openingRangesOn(branchA, monday.plusDays(1))).isEmpty();      // ngày nghỉ
        assertThat(branchQuery.openingRangesOn(branchA, monday.plusDays(6))).isEmpty();      // Chủ nhật nghỉ cố định
        assertThat(branchQuery.isHoliday(branchA, monday.plusDays(1))).isTrue();
        assertThat(branchQuery.isHoliday(branchA, monday)).isFalse();
        assertThat(branchQuery.isOpenAt(branchA, monday.atTime(9, 0))).isTrue();
        assertThat(branchQuery.isOpenAt(branchA, monday.atTime(12, 0))).isFalse();
        assertThat(branchQuery.isOpenAt(branchA, monday.plusDays(1).atTime(9, 0))).isFalse();
        assertThat(branchQuery.nextOpeningStart(branchA, monday.atTime(19, 0)))
                .contains(LocalDateTime.of(monday.plusDays(2), LocalTime.of(8, 0)));
    }

    @Test
    void branchQueryApiReadsServiceEnablementAndQuotaTables() {
        long serviceId = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, price) VALUES (?, 'GROOMING', 100000) RETURNING id
                """, Long.class, unique("Spa"));
        long draft = branchRow("DRAFT");
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)",
                branchA, serviceId);
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, false)",
                branchB, serviceId);
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)",
                draft, serviceId);

        assertThat(branchQuery.isServiceEnabled(branchA, serviceId)).isTrue();
        assertThat(branchQuery.isServiceEnabled(branchB, serviceId)).isFalse();
        assertThat(branchQuery.isServiceEnabled(branchA, serviceId + 1000)).isFalse();
        assertThat(branchQuery.findActiveBranchIdsEnablingService(serviceId)).containsExactly(branchA);

        long manager = staffAccount("BRANCH_MANAGER", branchA);
        LocalDate day = LocalDate.now(ZONE).plusDays(5);
        LocalTime nine = LocalTime.of(9, 0);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, nine)).isEqualTo(1);   // không có gì
        jdbc.update("INSERT INTO branch_quota_defaults (branch_id, service_group, default_quota, updated_by) "
                + "VALUES (?, 'MEDICAL', 4, ?)", branchA, manager);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, nine)).isEqualTo(4);   // mặc định
        jdbc.update("INSERT INTO slot_quotas (branch_id, service_group, slot_date, slot_start, quota, updated_by) "
                + "VALUES (?, 'MEDICAL', ?, ?, 0, ?)", branchA, day, nine, manager);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, nine)).isZero();       // khóa khung
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.MEDICAL, day, LocalTime.of(9, 30))).isEqualTo(4);
        assertThat(branchQuery.quotaFor(branchA, ServiceGroup.GROOMING, day, nine)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private long branchRow(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, unique("BranchIT"), status);
    }

    /** Giờ mở cửa Thứ Hai–Thứ Bảy 08:00–12:00 và 14:00–19:00, Chủ nhật nghỉ, bằng SQL. */
    private void hoursRows(long branchId, LocalDate effectiveFrom, boolean split) {
        for (int day = 1; day <= 7; day++) {
            if (day == 7) {
                jdbc.update("INSERT INTO opening_hours (branch_id, effective_from, day_of_week) VALUES (?, ?, ?)",
                        branchId, effectiveFrom, day);
            } else {
                jdbc.update("""
                        INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1, open_2, close_2)
                        VALUES (?, ?, ?, '08:00', '12:00', ?, ?)
                        """, branchId, effectiveFrom, day, split ? LocalTime.of(14, 0) : null,
                        split ? LocalTime.of(19, 0) : null);
            }
        }
    }

    private int rowsAt(long branchId, LocalDate effectiveFrom) {
        return jdbc.queryForObject("SELECT count(*) FROM opening_hours WHERE branch_id = ? AND effective_from = ?",
                Integer.class, branchId, effectiveFrom);
    }

    private String statusOf(long branchId) {
        return jdbc.queryForObject("SELECT status FROM branches WHERE id = ?", String.class, branchId);
    }

    private static Map<String, Object> range(String open, String close) {
        Map<String, Object> range = new HashMap<>();
        range.put("open", open);
        range.put("close", close);
        return range;
    }

    /** Body {@code SetOpeningHoursRequest}: Thứ Hai–Thứ Bảy 08:00–12:00 (+ 14:00–19:00 nếu {@code split}), Chủ nhật nghỉ. */
    private static Map<String, Object> hoursBody(LocalDate effectiveFrom, boolean split, boolean cancelAffected) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            Map<String, Object> day = new HashMap<>();
            day.put("dayOfWeek", d);
            List<Map<String, Object>> ranges = new ArrayList<>();
            if (d != 7) {
                ranges.add(range("08:00", "12:00"));
                if (split) {
                    ranges.add(range("14:00", "19:00"));
                }
            }
            day.put("ranges", ranges);
            days.add(day);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("effectiveFrom", effectiveFrom.toString());
        body.put("days", days);
        body.put("cancelAffected", cancelAffected);
        return body;
    }

    @SafeVarargs
    private static void overrideMonday(Map<String, Object> body, Map<String, Object>... ranges) {
        ((Map<String, Object>) ((List<?>) body.get("days")).get(0)).put("ranges", List.of(ranges));
    }

    private static Map<String, Object> newBranchBody(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("address", "1 Nguyễn Huệ");
        body.put("phone", "0281234567");
        body.put("latitude", 10.776889);
        body.put("longitude", 106.700806);
        return body;
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    private long staffAccount(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, "branch-it-" + UUID.randomUUID() + "@petcare.test", role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        return id;
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "BranchAdminIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).as("body of %s", response).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).as("body of %s", response).isNotNull();
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static List<Long> idsOf(List<Map<String, Object>> items, String key) {
        return items.stream().map(m -> ((Number) m.get(key)).longValue()).toList();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsKeys("message", "timestamp", "traceId");
    }
}
