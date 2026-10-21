package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.config.TimeConfig;

/**
 * {@code AppointmentQueryApi} thật trên Postgres (docs/adr/0050): kết quả từng query, tham gia transaction ghi của bên
 * gọi, và G1 — thêm ngày nghỉ chờ khóa {@code branches} của một lượt đặt đang chạy rồi thấy lịch đó. Ba API của
 * BE-2 ({@code BoardingQueryApi}, {@code PetQueryApi}, {@code CustomerQueryApi}) là mock trả rỗng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentQueryApiIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final LocalDate DAY = LocalDate.now(TimeConfig.BUSINESS_ZONE).plusDays(20);

    @MockitoBean
    private BoardingQueryApi boarding;
    @MockitoBean
    private PetQueryApi pets;
    @MockitoBean
    private CustomerQueryApi customers;

    @Autowired
    private AppointmentQueryApi api;
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

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long medical;
    private long staff;
    private long customer;
    private long otherCustomer;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        staff = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer();
        otherCustomer = data.counterCustomer();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private long appointment(long customerId, long branchId, LocalDate date, String time, String status) {
        return data.appointment(customerId, data.pet(customerId), branchId, medical, "MEDICAL", date,
                LocalTime.parse(time), status, staff);
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    private List<BookedSlot> bookedFrom(long branchId, LocalDate from) {
        return inTx(() -> api.findBookedFrom(branchId, from));
    }

    @Test
    void findBookedFromReturnsOnlyBookedOfThatBranchFromTheDateSortedBySlot() {
        long late = appointment(customer, branch, DAY.plusDays(1), "09:00", "BOOKED");
        long second = appointment(customer, branch, DAY, "10:00", "BOOKED");
        long first = appointment(otherCustomer, branch, DAY, "08:30", "BOOKED");
        appointment(customer, branch, DAY.minusDays(1), "08:30", "BOOKED");   // trước ngày
        appointment(customer, branch, DAY, "09:00", "CHECKED_IN");
        appointment(customer, branch, DAY, "09:30", "COMPLETED");
        appointment(customer, branch, DAY, "11:00", "NO_SHOW");
        long cancelled = appointment(customer, branch, DAY, "11:30", "CANCELLED");
        jdbc.update("UPDATE appointments SET cancel_source = 'STAFF' WHERE id = ?", cancelled);
        appointment(customer, otherBranch, DAY, "08:30", "BOOKED");           // chi nhánh khác

        List<BookedSlot> found = bookedFrom(branch, DAY);

        assertThat(found).extracting(BookedSlot::appointmentId).containsExactly(first, second, late);
        BookedSlot one = found.get(0);
        assertThat(one.branchId()).isEqualTo(branch);
        assertThat(one.customerId()).isEqualTo(otherCustomer);
        assertThat(one.slotDate()).isEqualTo(DAY);
        assertThat(one.slotStart()).isEqualTo(LocalTime.of(8, 30));
        assertThat(one.code()).isEqualTo(jdbc.queryForObject("SELECT code FROM appointments WHERE id = ?",
                String.class, first));
        assertThat(one.petId()).isEqualTo(jdbc.queryForObject("SELECT pet_id FROM appointments WHERE id = ?",
                Long.class, first));
        assertThat(bookedFrom(branch, DAY.plusDays(2))).isEmpty();
    }

    @Test
    void findBookedByCustomerSpansBranchesAndOnlyBooked() {
        long later = appointment(customer, otherBranch, DAY.plusDays(1), "08:30", "BOOKED");
        long sooner = appointment(customer, branch, DAY, "09:00", "BOOKED");
        appointment(customer, branch, DAY, "10:00", "CHECKED_IN");
        appointment(customer, branch, DAY, "10:30", "NO_SHOW");
        appointment(otherCustomer, branch, DAY, "11:00", "BOOKED");

        List<BookedSlot> found = inTx(() -> api.findBookedByCustomer(customer));

        assertThat(found).extracting(BookedSlot::appointmentId).containsExactly(sooner, later);
        assertThat(inTx(() -> api.findBookedByCustomer(otherCustomer))).hasSize(1);
        assertThat(inTx(() -> api.findBookedByCustomer(Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void existsCountsEveryStatus() {
        long pet = data.pet(customer);
        assertThat(inTx(() -> api.existsByPet(pet))).isFalse();
        assertThat(inTx(() -> api.existsByCustomer(customer))).isFalse();

        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "NO_SHOW",
                staff);

        assertThat(inTx(() -> api.existsByPet(pet))).isTrue();
        assertThat(inTx(() -> api.existsByCustomer(customer))).isTrue();
        assertThat(inTx(() -> api.existsByPet(data.pet(otherCustomer)))).isFalse();
        assertThat(inTx(() -> api.existsByCustomer(otherCustomer))).isFalse();
        assertThat(id).isPositive();
    }

    /**
     * Visit#1 (docs/adr/0059): bốn cột bất biến của lịch ở mọi trạng thái; không có thì rỗng. Projection không nạp entity,
     * nên {@code findByIdForUpdate} sau đó trong cùng transaction đọc lại dòng từ DB (thấy thay đổi ghi ngoài JPA), không
     * trả lại một entity cũ từ persistence context (bẫy docs/adr/0029 §4).
     */
    @Test
    void checkInRefReadsImmutableColumnsWithoutLoadingTheEntity() {
        long pet = data.pet(customer);
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "NO_SHOW",
                staff);

        assertThat(inTx(() -> api.findCheckInRef(id)))
                .contains(new AppointmentQueryApi.CheckInRef(id, branch, pet, medical));
        assertThat(inTx(() -> api.findCheckInRef(Long.MAX_VALUE))).isEmpty();

        String statusUnderLock = inTx(() -> {
            api.findCheckInRef(id);
            jdbc.update("UPDATE appointments SET status = 'CANCELLED', cancel_source = 'STAFF', late_cancel = false, "
                    + "cancelled_at = now() WHERE id = ?", id);
            return repository.findByIdForUpdate(id).orElseThrow().getStatus().name();
        });
        assertThat(statusUnderLock).isEqualTo("CANCELLED");
    }

    /**
     * Class {@code readOnly} <b>tham gia</b> transaction ghi của bên gọi: không chuyển session sang flush MANUAL (nếu
     * chuyển, thay đổi chưa flush của bên gọi sẽ mất khi commit) và không đổi cờ readOnly của transaction.
     */
    @Test
    void lookupJoinsCallerWriteTransaction() {
        long id = appointment(customer, branch, DAY, "08:30", "BOOKED");

        tx.executeWithoutResult(s -> {
            Appointment loaded = repository.findByIdForUpdate(id).orElseThrow();
            loaded.reschedule(DAY, LocalTime.of(11, 0));
            api.findBookedFrom(branch, DAY);
            api.findBookedByCustomer(customer);
            api.existsByPet(loaded.getPetId());
            api.existsByCustomer(customer);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        });

        assertThat(jdbc.queryForObject("SELECT slot_start::text FROM appointments WHERE id = ?", String.class, id))
                .isEqualTo("11:00:00");
        assertThat(jdbc.queryForObject("SELECT reschedule_count FROM appointments WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    /**
     * G1: ngày nghỉ khóa {@code branches} trước khi đọc lịch, nên chờ lượt đặt đang giữ khóa đó rồi thấy lịch nó vừa
     * commit → từ chối (BR-LH-10), không ghi ngày nghỉ. Đối chứng: không có lịch nào thì ngày nghỉ được tạo.
     */
    @Test
    void holidayWaitsForInFlightBookingAndSeesIt() throws Exception {
        long manager = data.staff("BRANCH_MANAGER", branch);
        String token = token(manager);
        long pet = data.pet(customer);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // "Lượt đặt đang chạy": giữ khóa chi nhánh, chèn lịch, commit khi được thả.
            Future<?> booking = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT status FROM branches WHERE id = ? FOR NO KEY UPDATE", branch);
                held.countDown();
                await(release);
                data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "BOOKED", staff);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> holiday = pool.submit(() -> addHoliday(token));
            awaitWaiting("%from branches%for no key update%");
            assertThat(holiday.isDone()).as("ngày nghỉ phải chờ khóa chi nhánh").isFalse();
            release.countDown();
            booking.get(30, TimeUnit.SECONDS);

            ResponseEntity<Map<String, Object>> response = holiday.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-LH-10)");
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ?", Long.class, branch))
                .isZero();

        jdbc.update("DELETE FROM appointments WHERE branch_id = ?", branch);
        assertThat(addHoliday(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ? AND holiday_date = ?",
                Long.class, branch, Date.valueOf(DAY))).isEqualTo(1);
    }

    private ResponseEntity<Map<String, Object>> addHoliday(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/branches/" + branch + "/holidays"),
                HttpMethod.POST, new HttpEntity<>("{\"holidayDate\":\"" + DAY + "\"}", headers), MAP);
    }

    private String token(long accountId) {
        return inTx(() -> sessions.open(accountId, "203.0.113.9", "AppointmentQueryApiIT")).accessToken();
    }

    private void awaitWaiting(String like) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?""",
                    Long.class, like);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("không thấy request chờ khóa (" + like + ")");
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
