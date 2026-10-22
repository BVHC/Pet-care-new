package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * Dữ liệu và HTTP dùng chung cho IT lưu trú (docs/adr/0065), era năm 2009. Mỗi lớp IT tạo chi nhánh, khách, thú riêng
 * (tên / email UUID) bằng SQL; {@link #cleanBoarding()} xóa theo thứ tự FK, con trước, chỉ những gì lớp IT đã tạo.
 * Chi nhánh mặc định {@code ACTIVE}, giờ mở cửa 08:00–19:00 cả tuần từ năm 2000.
 */
public abstract class BoardingItSupport {

    protected static final AtomicInteger SEQ = new AtomicInteger();
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    protected static final LocalDate TODAY = LocalDate.parse("2009-06-10");

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected SessionService sessions;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected MutableClock clock;

    private final List<Long> branches = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();
    private final List<Long> customers = new ArrayList<>();
    private final List<Long> pets = new ArrayList<>();
    private final List<Long> services = new ArrayList<>();

    @BeforeEach
    void resetBoardingClock() {
        clock.set(BoardingClockConfig.START);
    }

    @AfterEach
    void cleanBoarding() {
        if (!branches.isEmpty()) {
            String b = ids(branches);
            jdbc.update("DELETE FROM care_logs WHERE boarding_booking_id IN "
                    + "(SELECT id FROM boarding_bookings WHERE branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM boarding_check_ins WHERE boarding_booking_id IN "
                    + "(SELECT id FROM boarding_bookings WHERE branch_id IN (" + b + "))");
            jdbc.update("DELETE FROM weight_records WHERE boarding_booking_id IN "
                    + "(SELECT id FROM boarding_bookings WHERE branch_id IN (" + b + "))");
        }
        if (!pets.isEmpty()) {
            jdbc.update("DELETE FROM boarding_bookings WHERE pet_id IN (" + ids(pets) + ")");
            jdbc.update("DELETE FROM weight_records WHERE pet_id IN (" + ids(pets) + ")");
        }
        if (!branches.isEmpty()) {
            String b = ids(branches);
            jdbc.update("DELETE FROM boarding_bookings WHERE branch_id IN (" + b + ")");
            jdbc.update("DELETE FROM kennels WHERE branch_id IN (" + b + ")");
        }
        if (!customers.isEmpty()) {
            jdbc.update("DELETE FROM booking_restrictions WHERE customer_id IN (" + ids(customers) + ")");
            jdbc.update("DELETE FROM appointments WHERE customer_id IN (" + ids(customers) + ")");
        }
        if (!accounts.isEmpty()) {
            String a = ids(accounts);
            jdbc.update("DELETE FROM notifications WHERE account_id IN (" + a + ")");
            jdbc.update("DELETE FROM notification_outbox WHERE recipient_account_id IN (" + a + ")");
            jdbc.update("DELETE FROM notification_outbox WHERE recipient_email LIKE 'boarding-it-%'");
        }
        if (!pets.isEmpty()) {
            jdbc.update("DELETE FROM pets WHERE id IN (" + ids(pets) + ")");
        }
        if (!customers.isEmpty()) {
            jdbc.update("DELETE FROM customers WHERE id IN (" + ids(customers) + ")");
        }
        if (!branches.isEmpty()) {
            String b = ids(branches);
            for (String child : List.of("branch_services", "opening_hours", "holidays", "slot_quotas",
                    "branch_quota_defaults")) {
                jdbc.update("DELETE FROM " + child + " WHERE branch_id IN (" + b + ")");
            }
        }
        if (!accounts.isEmpty()) {
            String a = ids(accounts);
            jdbc.update("DELETE FROM sessions WHERE account_id IN (" + a + ")");
            jdbc.update("DELETE FROM staff_profiles WHERE account_id IN (" + a + ")");
        }
        if (!services.isEmpty()) {
            jdbc.update("DELETE FROM branch_services WHERE service_id IN (" + ids(services) + ")");
            jdbc.update("DELETE FROM kennel_types WHERE service_id IN (" + ids(services) + ")");
            jdbc.update("DELETE FROM services WHERE id IN (" + ids(services) + ")");
        }
        if (!branches.isEmpty()) {
            jdbc.update("DELETE FROM branches WHERE id IN (" + ids(branches) + ")");
        }
        if (!accounts.isEmpty()) {
            jdbc.update("DELETE FROM accounts WHERE id IN (" + ids(accounts) + ")");
        }
        branches.clear();
        accounts.clear();
        customers.clear();
        pets.clear();
        services.clear();
    }

    private static String ids(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    // ---------------------------------------------------------------- dữ liệu

    /** Chi nhánh có giờ mở cửa 08:00–19:00 cả tuần. */
    protected long newBranch(String status) {
        long id = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status, activated_at)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, ?, CASE WHEN ? = 'ACTIVE' THEN now() END)
                RETURNING id""", Long.class, "BoardingIT " + UUID.randomUUID(), status, status);
        branches.add(id);
        openingHours(id, LocalTime.of(8, 0), LocalTime.of(19, 0));
        return id;
    }

    protected long newBranch() {
        return newBranch("ACTIVE");
    }

    protected void openingHours(long branchId, LocalTime open, LocalTime close) {
        jdbc.update("DELETE FROM opening_hours WHERE branch_id = ?", branchId);
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2000-01-01', ?, ?, ?)""", branchId, day, open, close);
        }
    }

    protected void holiday(long branchId, LocalDate date, long createdBy) {
        jdbc.update("INSERT INTO holidays (branch_id, holiday_date, reason, created_by) VALUES (?, ?, 'IT', ?)",
                branchId, date, createdBy);
    }

    /** Loại chuồng (dịch vụ BOARDING + kennel_types) đã bật tại {@code branchId}. */
    protected long newKennelType(long branchId, String species, String maxWeightKg, long nightlyPrice) {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, price, price_is_from) VALUES (?, 'BOARDING', ?, false)
                RETURNING id""", Long.class, "Loại chuồng " + UUID.randomUUID(), nightlyPrice);
        services.add(id);
        jdbc.update("INSERT INTO kennel_types (service_id, species, max_weight_kg) VALUES (?, ?, CAST(? AS numeric))",
                id, species, maxWeightKg);
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true)", branchId,
                id);
        return id;
    }

    protected long newKennel(long branchId, long kennelTypeId, String code, String status) {
        return jdbc.queryForObject("""
                INSERT INTO kennels (branch_id, kennel_type_id, code, status) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, branchId, kennelTypeId, code, status);
    }

    /** Nhân viên {@code ACTIVE}; A05–A08 thuộc {@code branchId}. */
    protected long newStaff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0901234567', 'x', ?, 'ACTIVE') RETURNING id""", Long.class,
                "boarding-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test", role);
        accounts.add(id);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                "Boarding IT " + role, branchId);
        return id;
    }

    /** Tài khoản khách {@code ACTIVE} và hồ sơ online; trả {@code [accountId, customerId]}. */
    protected long[] newOnlineCustomer() {
        long accountId = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'x', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, "boarding-it-" + UUID.randomUUID() + "@petcare.test");
        accounts.add(accountId);
        long customerId = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, ?, 'ONLINE') RETURNING id""",
                Long.class, accountId, "Khách " + UUID.randomUUID());
        customers.add(customerId);
        return new long[] {accountId, customerId};
    }

    /** Hồ sơ tại quầy; {@code email} null = không email. */
    protected long newCounterCustomer(String email) {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel) VALUES (?, '0912345678', ?, 'COUNTER')
                RETURNING id""", Long.class, "Khách quầy " + UUID.randomUUID(), email);
        customers.add(id);
        return id;
    }

    protected long newPet(long customerId, String species) {
        long id = jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, ?, ?, 'MALE') RETURNING id""",
                Long.class, customerId, "Bé " + SEQ.incrementAndGet(), species);
        pets.add(id);
        return id;
    }

    protected void weigh(long petId, String kg) {
        jdbc.update("""
                INSERT INTO weight_records (pet_id, weight_kg, source, measured_at)
                VALUES (?, CAST(? AS numeric), 'OWNER', now())""", petId, kg);
    }

    /** Chèn thẳng một đặt chỗ ở trạng thái {@code status} (CHECKED_IN / OVERDUE cần {@code kennelId}). */
    protected long newBooking(long customerId, long petId, long branchId, long kennelTypeId, LocalDate in,
            LocalDate out, String status, Long kennelId, long bookedBy) {
        return jdbc.queryForObject("""
                INSERT INTO boarding_bookings (code, customer_id, pet_id, branch_id, kennel_type_id, kennel_id,
                        check_in_date, check_out_date, nightly_price, status, channel, booked_by,
                        late_cancel, cancel_source, end_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 150000, ?, 'COUNTER', ?, false,
                        CASE WHEN ? = 'CANCELLED' THEN 'STAFF' END,
                        CASE WHEN ? = 'CHECKED_OUT' THEN 'RETURNED' END)
                RETURNING id""", Long.class, "LTIT-" + UUID.randomUUID().toString().substring(0, 8), customerId,
                petId, branchId, kennelTypeId, kennelId, in, out, status, bookedBy, status, status);
    }

    protected long newBooking(long customerId, long petId, long branchId, long kennelTypeId, LocalDate in,
            LocalDate out, String status, long bookedBy) {
        return newBooking(customerId, petId, branchId, kennelTypeId, in, out, status, null, bookedBy);
    }

    protected String statusOf(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM boarding_bookings WHERE id = ?", String.class, bookingId);
    }

    protected String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "BoardingIT")).accessToken();
    }

    // ---------------------------------------------------------------- đua khóa

    /**
     * Chạy các lời gọi HTTP song song trong lúc một transaction khác giữ khóa dòng {@code branches} của
     * {@code branchId}: đợi (qua {@code pg_stat_activity}) tới khi cả hai lời gọi đều đang chờ khóa đó rồi mới nhả, nên
     * thứ tự thật sự do khóa quyết định. Trả kết quả theo thứ tự truyền vào.
     */
    protected List<ResponseEntity<Map<String, Object>>> racedUnderBranchLock(long branchId,
            List<Supplier<ResponseEntity<Map<String, Object>>>> calls) {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR NO KEY UPDATE", Long.class, branchId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(awaitLatch(locked)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> futures = calls.stream()
                    .map(call -> pool.submit(call::get)).toList();
            waitUntilWaitingOnBranchLock(calls.size());
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            release.countDown();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean awaitLatch(CountDownLatch latch) throws InterruptedException {
        return latch.await(30, TimeUnit.SECONDS);
    }

    private void waitUntilWaitingOnBranchLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT CAST(count(*) AS integer) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query LIKE '%FROM branches%'""", Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Không thấy " + expected + " lời gọi chờ khóa branches");
    }

    // ---------------------------------------------------------------- HTTP

    protected ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected ResponseEntity<Map<String, Object>> callRaw(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), MAP);
    }

    protected static Map<String, Object> data(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return map(response.getBody().get("data"));
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> list(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    protected static long number(Object value) {
        return ((Number) value).longValue();
    }

    protected static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).contains("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    protected static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
    }
}
