package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
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
import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.support.MutableClock;

/**
 * {@code AppointmentApi} thật trên Postgres (docs/adr/0050): race với đổi khung / hủy của khách và lễ tân (G2), quota và
 * BR-LH-05 không đổi sau check-in / hoàn tất (G3), hủy lượt trả quota và không tính vi phạm (G4), và cửa sổ check-in
 * đúng với bộ lọc {@code checkInReady} (G6). Đồng hồ {@code T0} = 2026-10-15 09:00 giờ Việt Nam; chi nhánh mở
 * 08:00–12:00; lịch mẫu hôm nay 09:30 (đúng biên sớm nhất +30').
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AppointmentVisitApiIT.TestBeans.class})
class AppointmentVisitApiIT {

    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalTime SLOT = LocalTime.of(9, 30);
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

    @MockitoBean
    private BoardingQueryApi boarding;

    @Autowired
    private AppointmentApi api;
    @Autowired
    private AppointmentRepository repository;
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
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        customerAccount = data.customerAccount();
        customer = data.onlineCustomer(customerAccount, "Nguyễn Văn An");
        pet = data.pet(customer);
        appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", TODAY, SLOT, "BOOKED", receptionist);
        receptionistToken = token(receptionist);
        customerToken = token(customerAccount);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // ============================================================ G2 — race

    /** Check-in lấy dòng trước: khách bấm hủy sau đọc lại dưới khóa thấy CHECKED_IN → 409. */
    @Test
    void checkInThenCustomerCancelIsConflict() throws Exception {
        List<String> results = whileRowHeld(List.of(checkIn(0), () -> cancelStatus(customerToken)));

        assertThat(results).containsExactly("OK", "409");
        assertThat(status(appointment)).isEqualTo("CHECKED_IN");
    }

    /** Khách hủy lấy dòng trước: check-in đọc lại dưới khóa thấy CANCELLED → 409. */
    @Test
    void customerCancelThenCheckInIsConflict() throws Exception {
        List<String> results = whileRowHeld(List.of(() -> cancelStatus(customerToken), checkIn(0)));

        assertThat(results).containsExactly("200", InvalidStateTransitionException.class.getSimpleName());
        assertThat(status(appointment)).isEqualTo("CANCELLED");
    }

    /** Check-in trước, lễ tân đổi khung sau → 409. */
    @Test
    void checkInThenRescheduleIsConflict() throws Exception {
        List<String> results = whileRowHeld(List.of(checkIn(0), () -> rescheduleStatus("10:00")));

        assertThat(results).containsExactly("OK", "409");
        assertThat(row(appointment)).containsEntry("status", "CHECKED_IN").containsEntry("reschedule_count", 0);
    }

    /** Đổi khung trước: check-in đọc khung mới dưới khóa (10:00, ngoài cửa sổ 09:30) → 400, không phải khung cũ. */
    @Test
    void rescheduleThenCheckInSeesTheNewSlot() throws Exception {
        List<String> results = whileRowHeld(List.of(() -> rescheduleStatus("10:00"), checkIn(0)));

        assertThat(results).containsExactly("200", BusinessRuleViolationException.class.getSimpleName());
        assertThat(row(appointment)).containsEntry("status", "BOOKED").containsEntry("reschedule_count", 1);
    }

    @Test
    void doubleCheckInSucceedsOnceAndTheSecondIsConflict() throws Exception {
        List<String> results = whileRowHeld(List.of(checkIn(0), checkIn(0)));

        assertThat(results).containsExactly("OK", InvalidStateTransitionException.class.getSimpleName());
        assertThat(status(appointment)).isEqualTo("CHECKED_IN");
    }

    // ============================================================ G3 / G4 — quota, BR-LH-05, vi phạm

    @Test
    void checkInAndCompleteKeepTheQuotaOccupiedAndBr05Applies() {
        assertThat(occupied()).isEqualTo(1);

        tx.executeWithoutResult(s -> api.checkIn(appointment, branch, clock.instant()));
        assertThat(occupied()).isEqualTo(1);
        tx.executeWithoutResult(s -> api.completeByVisit(appointment));
        assertThat(occupied()).isEqualTo(1);

        // BR-LH-05: thú vẫn có lịch nhóm Khám/Tiêm hôm nay (COMPLETED tính) → đặt thêm cùng nhóm cùng ngày bị chặn.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        body.put("slotDate", TODAY.toString());
        body.put("slotStart", "11:00");
        ResponseEntity<Map<String, Object>> rule = exchange(receptionistToken, "/api/appointments", body);
        assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) rule.getBody().get("message")).endsWith("(BR-LH-05)");
    }

    @Test
    void cancelByVisitFreesTheQuotaAndIsNoViolation() {
        tx.executeWithoutResult(s -> api.checkIn(appointment, branch, clock.instant()));
        assertThat(occupied()).isEqualTo(1);

        tx.executeWithoutResult(s -> api.cancelByVisit(appointment, clock.instant()));

        assertThat(occupied()).isZero();
        assertThat(row(appointment)).containsEntry("status", "CANCELLED").containsEntry("cancel_source", "STAFF")
                .containsEntry("late_cancel", false);
        int violations = tx.execute(s -> repository.countViolationsAfter(customer, Instant.EPOCH));
        assertThat(violations).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Long.class,
                customer)).isZero();
    }

    // ============================================================ G6 — cửa sổ check-in

    /** Với cùng {@code now}, {@code checkInReady=true} trả đúng tập lịch mà {@code checkIn} không từ chối BR-TN-02. */
    @Test
    void checkInReadyListEqualsWhatCheckInAccepts() {
        List<Long> ids = new ArrayList<>();
        for (String time : List.of("07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "11:30")) {
            ids.add(data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", TODAY,
                    LocalTime.parse(time), "BOOKED", receptionist));
        }
        ids.add(appointment);
        for (String now : List.of("2026-10-15T00:59:00Z", "2026-10-15T01:30:00Z", "2026-10-15T02:00:00Z",
                "2026-10-15T02:20:00Z", "2026-10-15T03:00:00Z", "2026-10-15T04:31:00Z")) {
            clock.set(Instant.parse(now));

            Set<Long> accepted = new TreeSet<>();
            for (long id : ids) {
                if (checkInAccepted(id)) {
                    accepted.add(id);
                }
            }

            assertThat(readyIds()).as("now=%s", now).isEqualTo(accepted);
        }
    }

    @Test
    void windowBoundariesAreInclusiveOnTheRealTable() {
        // at = giờ hẹn 09:30 ∓ 30'/31' (đồng hồ T0 = 09:00 chỉ dùng để mở phiên).
        Instant slot = Instant.parse("2026-10-15T02:30:00Z");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.checkIn(appointment, branch,
                slot.minusSeconds(31 * 60)))).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.checkIn(appointment, branch,
                slot.plusSeconds(31 * 60)))).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(status(appointment)).isEqualTo("BOOKED");

        tx.executeWithoutResult(s -> api.checkIn(appointment, branch, slot.minusSeconds(30 * 60)));
        assertThat(status(appointment)).isEqualTo("CHECKED_IN");
    }

    // ============================================================ helpers

    private boolean checkInAccepted(long id) {
        boolean[] accepted = {false};
        tx.executeWithoutResult(s -> {
            try {
                api.checkIn(id, branch, clock.instant());
                accepted[0] = true;
            } catch (BusinessRuleViolationException rejected) {
                accepted[0] = false;
            } finally {
                s.setRollbackOnly();
            }
        });
        return accepted[0];
    }

    @SuppressWarnings("unchecked")
    private Set<Long> readyIds() {
        ResponseEntity<Map<String, Object>> response = http.exchange(URI.create(http.getRootUri()
                + "/api/appointments?branchId=" + branch + "&checkInReady=true&size=100"), HttpMethod.GET,
                new HttpEntity<>(bearer(receptionistToken)), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> page = (Map<String, Object>) response.getBody().get("data");
        Set<Long> ids = new TreeSet<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) page.get("content")) {
            ids.add(((Number) item.get("appointmentId")).longValue());
        }
        return ids;
    }

    private int occupied() {
        return repository.countActiveInSlot(branch, "MEDICAL", TODAY, SLOT);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, reschedule_count, cancel_source, late_cancel FROM appointments WHERE id = ?""", id);
    }

    /** Một lời gọi {@code checkIn} thuần: "OK" hoặc tên lớp lỗi. */
    private Callable<String> checkIn(int minutesFromNow) {
        return () -> {
            try {
                tx.executeWithoutResult(s -> api.checkIn(appointment, branch,
                        clock.instant().plusSeconds(minutesFromNow * 60L)));
                return "OK";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };
    }

    private String cancelStatus(String token) {
        return String.valueOf(post(token, "cancel", null).getStatusCode().value());
    }

    private String rescheduleStatus(String start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", TODAY.toString());
        body.put("slotStart", start);
        return String.valueOf(post(receptionistToken, "reschedule", body).getStatusCode().value());
    }

    private ResponseEntity<Map<String, Object>> post(String token, String action, Object body) {
        return exchange(token, "/api/appointments/" + appointment + "/" + action, body);
    }

    private ResponseEntity<Map<String, Object>> exchange(String token, String path, Object body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST, new HttpEntity<>(body, headers),
                MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "AppointmentVisitApiIT")).accessToken();
    }

    /**
     * Giữ khóa dòng lịch hẹn trong một transaction khác, bắn lần lượt từng request và chờ nó nằm trên
     * {@code pg_stat_activity}, rồi thả: thứ tự thắng khóa = thứ tự trong danh sách.
     */
    private List<String> whileRowHeld(List<Callable<String>> requests) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE", appointment);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<String>> calls = new ArrayList<>();
            for (Callable<String> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(calls.size());
            }
            for (Future<String> call : calls) {
                assertThat(call.isDone()).as("request phải chờ khóa dòng").isFalse();
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<String> results = new ArrayList<>();
            for (Future<String> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, WAITING_ON_ROW);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy " + count + " request chờ khóa dòng lịch hẹn");
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
