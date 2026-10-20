package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.job.NoShowJob;
import com.petcare.module.appointment.service.AppointmentNoShowService;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.appointment.service.NoShowCutoff;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.support.MutableClock;

import jakarta.persistence.EntityManagerFactory;

/**
 * ST05 (T25 phần 2, docs/adr/0048, nợ D037) nhìn từ các luồng khác, trên bean thật của customer / catalog / branch:
 * đọc qua GET #3 / #4 và {@code AppointmentQueryApi}, check-in sau NO_SHOW, đặt online sau hạn chế do job tạo, race với
 * hủy muộn cùng khách (G4) và với đánh dấu thú mất (D036), số câu SQL theo số lịch. Đồng hồ {@link MutableClock} năm 2000
 * như {@code NoShowIT}; {@code BoardingQueryApi} là mock (D033) trả 0 vi phạm. Context bật Hibernate Statistics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NoShowFlowIT.TestBeans.class})
class NoShowFlowIT {

    private static final LocalDate DAY = LocalDate.of(2000, 6, 1);
    private static final Instant T0 = vn(DAY, LocalTime.of(10, 30));
    private static final String WAITING_ON_ADVISORY = "%pg_advisory_xact_lock%";
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
    @Autowired
    private NoShowJob job;
    @Autowired
    private AppointmentNoShowService service;
    @Autowired
    private AppointmentApi appointmentApi;
    @Autowired
    private AppointmentQueryApi queryApi;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long medical;
    private long receptionist;
    private String receptionistToken;
    private long customer;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(1999, 1, 1), LocalTime.of(8, 0), LocalTime.of(17, 0), null, null);
        data.branchService(branch, medical, true);
        receptionist = data.staff("RECEPTIONIST", branch);
        receptionistToken = token(receptionist);
        customer = data.counterCustomer();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    // --- đọc sau NO_SHOW ---------------------------------------------------------------------------------------------

    @Test
    void noShowIsVisibleThroughTheApiAndCheckInIsRejected() {
        long id = booked(customer, DAY, LocalTime.of(9, 0), "BOOKED");

        job.run();

        Map<String, Object> detail = data(get("/api/appointments/" + id, receptionistToken));
        assertThat(detail).containsEntry("status", "NO_SHOW").containsEntry("lateCancel", false)
                .containsEntry("cancelSource", null).containsEntry("cancelledAt", null)
                .containsEntry("canCancel", false).containsEntry("canReschedule", false);
        assertThat(ids(get("/api/appointments?branchId=" + branch + "&status=NO_SHOW", receptionistToken)))
                .contains((int) id);
        assertThat(ids(get("/api/appointments?branchId=" + branch + "&status=BOOKED", receptionistToken)))
                .doesNotContain((int) id);
        assertThat(queryApi.findBookedFrom(branch, DAY.minusDays(1)))
                .extracting(AppointmentQueryApi.BookedSlot::appointmentId).doesNotContain(id);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> appointmentApi.checkIn(id, branch, clock.instant())))
                .as("G2: check-in sau NO_SHOW").isInstanceOf(InvalidStateTransitionException.class);
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    // --- hạn chế đặt online do ST05 tạo --------------------------------------------------------------------------------

    @Test
    void restrictionCreatedByTheJobBlocksOnlineBookingButNotTheReceptionist() {
        long account = data.customerAccount("noshow-flow-" + System.nanoTime() + "@example.com", "ACTIVE");
        long online = data.onlineCustomer(account, "Khách Vắng Mặt");
        for (int day = 20; day <= 22; day++) {
            booked(online, LocalDate.of(2000, 5, day), LocalTime.of(9, 0), "BOOKED");
        }
        long pet = data.pet(online);
        LocalDate slotDate = DAY.plusDays(2);

        job.run();
        assertThat(restrictionCount(online)).isEqualTo(1);

        ResponseEntity<Map<String, Object>> online400 = book(token(account), pet, slotDate, "09:00");
        assertThat(online400.getStatusCode()).as("%s", online400.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(online400.getBody()).containsEntry("errorCode", "BUSINESS_RULE_VIOLATION");
        Instant endsAt = jdbc.queryForObject("SELECT ends_at FROM booking_restrictions WHERE customer_id = ?",
                java.sql.Timestamp.class, online).toInstant();
        assertThat((String) online400.getBody().get("message")).as("D035: kèm thời điểm hết hạn chế")
                .contains("đến " + java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                        .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(endsAt))
                .endsWith("(BR-LH-09)");
        assertThat(bookedOfPet(pet)).isZero();

        ResponseEntity<Map<String, Object>> counter201 = book(receptionistToken, pet, slotDate, "09:00");
        assertThat(counter201.getStatusCode()).as("%s", counter201.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(bookedOfPet(pet)).isEqualTo(1);
    }

    // --- race ----------------------------------------------------------------------------------------------------------

    /**
     * G4: một vi phạm có sẵn, rồi NO_SHOW và hủy muộn của cùng khách chạy song song → đúng 1 hạn chế. Cả hai đã ghi vi
     * phạm và đứng chờ advisory 1002 do test giữ; hủy muộn xếp hàng trước nên được khóa trước và commit trước (đếm 2,
     * chưa đủ); NO_SHOW được khóa sau, câu đếm READ COMMITTED thấy vi phạm vừa commit (đếm 3). Đổi {@code markNoShow} sang
     * REPEATABLE READ thì snapshot có từ câu khóa dòng, không thấy hủy muộn → 0 hạn chế (mutation làm đỏ test này).
     */
    @Test
    void noShowParallelToLateCancelOfTheSameCustomerCreatesExactlyOneRestriction() throws Exception {
        booked(customer, LocalDate.of(2000, 5, 10), LocalTime.of(9, 0), "NO_SHOW");
        long overdue = booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long lateCancel = booked(customer, DAY, LocalTime.of(15, 0), "BOOKED");
        NoShowCutoff cutoff = service.cutoff(clock.instant());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(1002, ?)", Integer.class, (int) customer);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> cancel = pool.submit(() -> cancel(receptionistToken, lateCancel));
            awaitWaiting(WAITING_ON_ADVISORY, 1);
            Future<NoShowResult> noShow = pool.submit(() -> service.markNoShow(overdue, cutoff));
            awaitWaiting(WAITING_ON_ADVISORY, 2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            ResponseEntity<Map<String, Object>> cancelled = cancel.get(20, TimeUnit.SECONDS);
            assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(noShow.get(20, TimeUnit.SECONDS)).isEqualTo(NoShowResult.MARKED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(status(overdue)).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT late_cancel FROM appointments WHERE id = ?", Boolean.class, lateCancel))
                .isTrue();
        assertThat(restrictionCount(customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT violation_count FROM booking_restrictions WHERE customer_id = ?",
                Integer.class, customer)).isEqualTo(3);
    }

    /**
     * Thú mất ∥ ST05 (D036 mục 2, docs/adr/0048 "Không đảm bảo"): đánh dấu thú mất đang chờ dòng lịch hẹn thì ST05 bỏ
     * qua dòng đó (SKIP LOCKED, không chờ); khi dòng được nhả, hủy do phòng khám thắng → đúng một trạng thái cuối
     * {@code CANCELLED} / {@code CLINIC}, không tính vi phạm, lượt sau không đụng.
     */
    @Test
    void deceasedParallelNoShowEndsInExactlyOneFinalState() throws Exception {
        long pet = data.pet(customer);
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2000, 5, 20),
                LocalTime.of(9, 0), "BOOKED", receptionist);
        NoShowCutoff cutoff = service.cutoff(clock.instant());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map<String, Object>>> deceased = pool.submit(() -> call(HttpMethod.POST,
                    "/api/pets/" + pet + "/mark-deceased", receptionistToken,
                    Map.of("deceasedOn", DAY.toString(), "confirmed", true)));
            awaitWaiting(WAITING_ON_ROW, 1);

            NoShowResult result = CompletableFuture.supplyAsync(() -> service.markNoShow(id, cutoff))
                    .get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(NoShowResult.SKIPPED);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            ResponseEntity<Map<String, Object>> response = deceased.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        job.run();

        assertThat(jdbc.queryForMap("SELECT status, cancel_source, late_cancel FROM appointments WHERE id = ?", id))
                .containsEntry("status", "CANCELLED").containsEntry("cancel_source", "CLINIC")
                .containsEntry("late_cancel", false);
        assertThat(restrictionCount(customer)).isZero();
    }

    /** Thứ tự ngược lại: ST05 commit trước thì lịch giữ {@code NO_SHOW} khi thú mất sau đó (D036, đã xác nhận — docs/adr/0052). */
    @Test
    void noShowCommittedBeforeDeceasedStaysNoShow() {
        long pet = data.pet(customer);
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", LocalDate.of(2000, 5, 20),
                LocalTime.of(9, 0), "BOOKED", receptionist);

        job.run();
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/pets/" + pet + "/mark-deceased",
                receptionistToken, Map.of("deceasedOn", DAY.toString(), "confirmed", true));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("NO_SHOW");
    }

    // --- số câu SQL ------------------------------------------------------------------------------------------------------

    /**
     * Không N+1: một lượt với N lịch quá hạn (mỗi lịch một khách, không ai tới ngưỡng) = 1 câu quét + N × k câu, k không
     * đổi giữa N = 1 và N = 50 (dưới {@code batch-size} nên một lô).
     */
    @Test
    void statementsGrowLinearlyWithOverdueAppointments() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();

        booked(customer, LocalDate.of(2000, 5, 20), LocalTime.of(9, 0), "BOOKED");
        long one = run(statistics);
        long perAppointment = one - 1;

        for (int i = 0; i < 50; i++) {
            booked(data.counterCustomer(), LocalDate.of(2000, 5, 21), LocalTime.of(9, 0), "BOOKED");
        }
        long fifty = run(statistics);

        assertThat(perAppointment).as("mỗi lịch: khóa, đọc, UPDATE, advisory, 3 câu đánh giá").isBetween(1L, 8L);
        assertThat(fifty).isEqualTo(1 + 50 * perAppointment);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------------

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private long run(Statistics statistics) {
        statistics.clear();
        job.run();
        return statistics.getPrepareStatementCount();
    }

    private long booked(long ownerCustomer, LocalDate date, LocalTime start, String status) {
        return data.appointment(ownerCustomer, data.pet(ownerCustomer), branch, medical, "MEDICAL", date, start, status,
                receptionist);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, id);
    }

    private int restrictionCount(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_restrictions WHERE customer_id = ?", Integer.class,
                customerId);
    }

    private int bookedOfPet(long petId) {
        return jdbc.queryForObject("SELECT count(*) FROM appointments WHERE pet_id = ? AND status = 'BOOKED'",
                Integer.class, petId);
    }

    private String token(long accountId) {
        return tx.execute(s -> sessions.open(accountId, "203.0.113.9", "NoShowFlowIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
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

    private ResponseEntity<Map<String, Object>> cancel(String token, long id) {
        return call(HttpMethod.POST, "/api/appointments/" + id + "/cancel", token, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids(ResponseEntity<Map<String, Object>> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) data(response).get("content");
        return content.stream().map(row -> row.get("appointmentId")).toList();
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
