package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * T16 phần 1 — xem khung giờ trống {@code GET /api/branches/{branchId}/available-slots} (appointment-v1 #1, UC39;
 * docs/adr/0046) qua HTTP trên Postgres 17 thật, với {@code BranchQueryService} / {@code CatalogQueryService} thật (không
 * mock module khác). Dữ liệu chèn bằng SQL qua {@link AppointmentItSupport}, xóa ở {@code @AfterEach}; chi nhánh ACTIVE
 * tên UUID, không có VET hay BRANCH_MANAGER nên không đổi kết quả của IT khác.
 * <p>
 * Đồng hồ {@code T0} = 2026-10-15 09:00 giờ Việt Nam. Chi nhánh mở 7 ngày 08:00–10:00 và 14:15–15:15 (giờ lẻ, nghỉ
 * trưa) → khung 08:00, 08:30, 09:00, 09:30, 14:15, 14:45.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AvailableSlotsIT.TestBeans.class})
class AvailableSlotsIT {

    private static final Instant T0 = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate DAY = TODAY.plusDays(2);
    private static final List<String> ALL = List.of("08:00", "08:30", "09:00", "09:30", "14:15", "14:45");
    private static final List<String> SLOT_KEYS = List.of("slotStart", "slotEnd", "quota", "booked", "status");
    private static final String HH_MM = "^[0-2][0-9]:[0-5][0-9]$";
    private static final String TX_STATE = """
            SELECT current_setting('transaction_isolation') || '|' || current_setting('transaction_read_only')
                   || '|' || pg_backend_pid()
            """;
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

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MutableClock clock;

    @MockitoSpyBean
    private AppointmentRepository repository;

    private volatile Runnable beforeCount;
    private volatile RuntimeException failCount;
    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long medical;
    private long grooming;
    private long receptionist;
    private long customer;
    private String receptionistToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        for (long b : List.of(branch, otherBranch)) {
            data.openingHours(b, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(10, 0),
                    LocalTime.of(14, 15), LocalTime.of(15, 15));
        }
        medical = data.service("MEDICAL");
        grooming = data.service("GROOMING");
        for (long b : List.of(branch, otherBranch)) {
            data.branchService(b, medical, true);
            data.branchService(b, grooming, true);
        }
        receptionist = data.staff("RECEPTIONIST", branch);
        customer = data.customerAccount();
        receptionistToken = token(receptionist);
        customerToken = token(customer);

        Answer<?> real = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Runnable hook = beforeCount;
            if (hook != null) {
                hook.run();
            }
            RuntimeException failure = failCount;
            if (failure != null) {
                failCount = null;
                throw failure;
            }
            return real.answer(invocation);
        }).when(repository).countActiveBySlot(anyLong(), anyString(), any());
    }

    @AfterEach
    void tearDown() {
        beforeCount = null;
        failCount = null;
        jdbc.update("DELETE FROM appointments WHERE code LIKE 'LH-IT-SNAP%'");
        data.cleanUp();
    }

    // ================================================================ nội dung, định dạng

    /**
     * Mỗi khung một giá trị riêng để bắt gán nhầm / lệch khung: 08:30 có 1 lịch, 09:00 có 2 (CHECKED_IN + COMPLETED),
     * 09:30 có 3 với quota riêng 2 (vượt quota vẫn hiện số thật), 14:15 chỉ có CANCELLED + NO_SHOW (trả quota), 14:45 quota
     * riêng 0. Lịch nhóm khác, chi nhánh khác, ngày khác không được đếm.
     */
    @Test
    void returnsEverySlotWithQuotaBookedAndStatusInContractShape() {
        long staff = receptionist;
        data.quotaDefault(branch, "MEDICAL", 2, staff);
        data.slotQuota(branch, "MEDICAL", DAY, LocalTime.of(9, 30), 2, staff);
        data.slotQuota(branch, "MEDICAL", DAY, LocalTime.of(14, 45), 0, staff);
        book(branch, medical, "MEDICAL", DAY, "08:30", "BOOKED");
        book(branch, medical, "MEDICAL", DAY, "09:00", "CHECKED_IN");
        book(branch, medical, "MEDICAL", DAY, "09:00", "COMPLETED");
        for (int i = 0; i < 3; i++) {
            book(branch, medical, "MEDICAL", DAY, "09:30", "BOOKED");
        }
        book(branch, medical, "MEDICAL", DAY, "14:15", "CANCELLED");
        book(branch, medical, "MEDICAL", DAY, "14:15", "NO_SHOW");
        book(branch, grooming, "GROOMING", DAY, "08:00", "BOOKED");
        book(otherBranch, medical, "MEDICAL", DAY, "08:00", "BOOKED");
        book(branch, medical, "MEDICAL", DAY.plusDays(1), "08:00", "BOOKED");

        ResponseEntity<Map<String, Object>> response = get(customerToken, branch, medical, DAY.toString());
        List<Map<String, Object>> slots = ok(response);

        assertThat(response.getBody()).containsEntry("code", 200);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot).containsOnlyKeys(SLOT_KEYS);
            assertThat((String) slot.get("slotStart")).matches(HH_MM);
            assertThat((String) slot.get("slotEnd")).matches(HH_MM);
            assertThat(slot.get("quota")).isInstanceOf(Integer.class);
            assertThat(slot.get("booked")).isInstanceOf(Integer.class);
            assertThat(slot.get("status")).isIn("AVAILABLE", "FULL");
        });
        assertThat(slots).containsExactly(
                slot("08:00", "08:30", 2, 0, "AVAILABLE"),
                slot("08:30", "09:00", 2, 1, "AVAILABLE"),
                slot("09:00", "09:30", 2, 2, "FULL"),
                slot("09:30", "10:00", 2, 3, "FULL"),
                slot("14:15", "14:45", 2, 0, "AVAILABLE"),
                slot("14:45", "15:15", 0, 0, "FULL"));
    }

    @Test
    void quotaFallsBackToOneWithoutAnyConfigurationAndGroupsAreSeparate() {
        data.quotaDefault(branch, "MEDICAL", 5, receptionist);
        book(branch, grooming, "GROOMING", DAY, "08:00", "BOOKED");

        List<Map<String, Object>> slots = ok(get(customerToken, branch, grooming, DAY.toString()));

        assertThat(slots).hasSize(6);
        assertThat(slots.get(0)).isEqualTo(slot("08:00", "08:30", 1, 1, "FULL"));
        assertThat(slots.subList(1, 6)).allSatisfy(slot -> assertThat(slot).containsEntry("quota", 1)
                .containsEntry("booked", 0).containsEntry("status", "AVAILABLE"));
    }

    @Test
    void holidayClosedDayAndBranchWithoutHoursAreEmptyArrays() {
        data.holiday(branch, DAY, receptionist);
        assertThat(ok(get(customerToken, branch, medical, DAY.toString()))).isEmpty();

        long noHours = data.branch("ACTIVE");
        data.branchService(noHours, medical, true);
        assertThat(ok(get(customerToken, noHours, medical, DAY.toString()))).isEmpty();

        long closedSundays = data.branch("ACTIVE");
        data.branchService(closedSundays, medical, true);
        for (int day = 1; day <= 6; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2020-01-01', ?, TIME '08:00', TIME '09:00')""", closedSundays, day);
        }
        LocalDate sunday = LocalDate.of(2026, 10, 18);
        assertThat(ok(get(customerToken, closedSundays, medical, sunday.toString()))).isEmpty();
        assertThat(ok(get(customerToken, closedSundays, medical, sunday.minusDays(1).toString()))).hasSize(2);
    }

    // ================================================================ cửa sổ BR-LH-04

    @Test
    void customerSeesOnlySlotsTwentyFourHoursAheadAndAtMostThirtyDays() {
        assertThat(starts(ok(get(customerToken, branch, medical, TODAY.toString())))).isEmpty();
        assertThat(starts(ok(get(customerToken, branch, medical, TODAY.plusDays(1).toString()))))
                .as("đúng 24 giờ (09:00) vẫn được").containsExactly("09:00", "09:30", "14:15", "14:45");
        assertThat(starts(ok(get(customerToken, branch, medical, TODAY.plusDays(30).toString())))).isEqualTo(ALL);
        assertThat(ok(get(customerToken, branch, medical, TODAY.plusDays(31).toString()))).isEmpty();
        assertThat(ok(get(customerToken, branch, medical, TODAY.minusDays(1).toString()))).isEmpty();
    }

    @Test
    void customerWindowFollowsTheClock() {
        clock.set(T0.plusSeconds(60));
        assertThat(starts(ok(get(customerToken, branch, medical, TODAY.plusDays(1).toString()))))
                .containsExactly("09:30", "14:15", "14:45");
    }

    @Test
    void receptionistSeesEverySlotNotYetStarted() {
        assertThat(starts(ok(get(receptionistToken, branch, medical, TODAY.toString()))))
                .as("09:00 đã bắt đầu đúng lúc này").containsExactly("09:30", "14:15", "14:45");
        assertThat(starts(ok(get(receptionistToken, branch, medical, TODAY.plusDays(60).toString())))).isEqualTo(ALL);
        assertThat(ok(get(receptionistToken, branch, medical, TODAY.minusDays(1).toString()))).isEmpty();
    }

    // ================================================================ phân quyền

    @Test
    void anonymousAndInvalidSessionsAre401() {
        assertError(get(null, branch, medical, DAY.toString()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(get("not-a-jwt", branch, medical, DAY.toString()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                null);

        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", customer);
        assertError(get(customerToken, branch, medical, DAY.toString()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "VET", "CARETAKER"})
    void otherRolesAre403WithoutAudit(String role) {
        boolean branchScoped = !"ADMIN".equals(role) && !"SUPER_MANAGER".equals(role);
        long account = data.staff(role, branchScoped ? branch : null);
        long auditsBefore = audits(account);

        assertError(get(token(account), branch, medical, DAY.toString()), HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                null);
        assertThat(audits(account)).isEqualTo(auditsBefore);
    }

    @Test
    void customerMayViewAnyActiveBranchWithoutBranchScope() {
        assertThat(ok(get(customerToken, otherBranch, medical, DAY.toString()))).hasSize(6);
    }

    @Test
    void receptionistOfAnotherBranchIs403BeforeExistenceIsRevealed() {
        assertError(get(receptionistToken, otherBranch, medical, DAY.toString()), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertError(get(receptionistToken, 999_999_999L, medical, DAY.toString()), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        long draft = data.branch("DRAFT");
        assertError(get(receptionistToken, draft, 999_999_999L, DAY.toString()), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
    }

    @Test
    void transferredReceptionistFollowsTheNewBranchWithTheSameToken() {
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, receptionist);

        assertError(get(receptionistToken, branch, medical, DAY.toString()), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH", null);
        assertThat(ok(get(receptionistToken, otherBranch, medical, DAY.toString()))).hasSize(6);
    }

    @Test
    void receptionistWhoMustChangePasswordIsBlocked() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);

        ResponseEntity<Map<String, Object>> response = get(receptionistToken, branch, medical, DAY.toString());

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    // ================================================================ 404 / 400 nghiệp vụ

    @Test
    void draftBranchLooksExactlyLikeAMissingOneToCustomers() {
        long draft = data.branch("DRAFT");
        data.branchService(draft, medical, true);

        ResponseEntity<Map<String, Object>> draftResponse = get(customerToken, draft, medical, DAY.toString());
        assertError(draftResponse, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy chi nhánh #" + draft);

        jdbc.update("DELETE FROM branch_services WHERE branch_id = ?", draft);
        jdbc.update("DELETE FROM branches WHERE id = ?", draft);
        ResponseEntity<Map<String, Object>> missing = get(customerToken, draft, medical, DAY.toString());
        assertError(missing, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", null);
        assertThat(withoutVolatile(missing.getBody())).isEqualTo(withoutVolatile(draftResponse.getBody()));
    }

    @Test
    void receptionistOfDraftBranchGetsBrLh01() {
        long draft = data.branch("DRAFT");
        long draftReceptionist = data.staff("RECEPTIONIST", draft);

        assertError(get(token(draftReceptionist), draft, medical, DAY.toString()), HttpStatus.BAD_REQUEST,
                "BUSINESS_RULE_VIOLATION", "Chi nhánh chưa nhận đặt lịch (BR-LH-01)");
    }

    @Test
    void missingServiceIs404() {
        assertError(get(customerToken, branch, 999_999_999L, DAY.toString()), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "Không tìm thấy dịch vụ #999999999");
        assertError(get(receptionistToken, branch, -1L, DAY.toString()), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND", "Không tìm thấy dịch vụ #-1");
        assertError(get(customerToken, -5L, medical, DAY.toString()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy chi nhánh #-5");
    }

    @Test
    void servicesThatCannotBeBookedAreBrLh01() {
        String message = "Dịch vụ không đặt được tại chi nhánh này (BR-LH-01)";
        long inactive = data.service("MEDICAL");
        data.branchService(branch, inactive, true);
        jdbc.update("UPDATE services SET is_active = false WHERE id = ?", inactive);
        long boarding = data.service("BOARDING");
        data.branchService(branch, boarding, true);
        long disabled = data.service("GROOMING");
        data.branchService(branch, disabled, false);
        long notConfigured = data.service("MEDICAL");

        for (long service : List.of(inactive, boarding, disabled, notConfigured)) {
            assertError(get(customerToken, branch, service, DAY.toString()), HttpStatus.BAD_REQUEST,
                    "BUSINESS_RULE_VIOLATION", message);
            assertError(get(receptionistToken, branch, service, DAY.toString()), HttpStatus.BAD_REQUEST,
                    "BUSINESS_RULE_VIOLATION", message);
        }
    }

    // ================================================================ tham số

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "serviceId={s}                      | Thiếu tham số 'date'",
            "date=2026-10-17                    | Thiếu tham số 'serviceId'",
            "serviceId=abc&date=2026-10-17      | Tham số 'serviceId' sai kiểu dữ liệu",
            "serviceId=1.5&date=2026-10-17      | Tham số 'serviceId' sai kiểu dữ liệu",
            "serviceId=99999999999999999999&date=2026-10-17 | Tham số 'serviceId' sai kiểu dữ liệu",
            "serviceId={s}&date=15/10/2026      | Tham số 'date' sai kiểu dữ liệu",
            "serviceId={s}&date=2026-02-30      | Tham số 'date' sai kiểu dữ liệu",
            "serviceId={s}&date=2026-13-01      | Tham số 'date' sai kiểu dữ liệu"})
    void malformedParametersAre400(String query, String message) {
        String rawQuery = query.replace("{s}", String.valueOf(medical));

        assertError(raw(customerToken, "/api/branches/" + branch + "/available-slots?" + rawQuery),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", message);
    }

    @Test
    void blankParametersAre400() {
        assertError(raw(customerToken, "/api/branches/" + branch + "/available-slots?serviceId=" + medical + "&date="),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Thiếu tham số 'date'");
        assertError(raw(customerToken, "/api/branches/" + branch + "/available-slots?serviceId=&date=" + DAY),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Thiếu tham số 'serviceId'");
    }

    @Test
    void malformedBranchIdIs400AndParametersAreCheckedBeforeRole() {
        assertError(raw(customerToken, "/api/branches/abc/available-slots?serviceId=" + medical + "&date=" + DAY),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Tham số 'branchId' sai kiểu dữ liệu");

        String vetToken = token(data.staff("VET", branch));
        assertError(raw(vetToken, "/api/branches/" + branch + "/available-slots?serviceId=" + medical + "&date=abc"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Tham số 'date' sai kiểu dữ liệu");
        assertError(get(vetToken, branch, medical, DAY.toString()), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
    }

    @Test
    void isoDateIsAcceptedAndUnknownParametersAreIgnored() {
        assertThat(ok(raw(customerToken, "/api/branches/" + branch + "/available-slots?serviceId=" + medical
                + "&date=" + DAY + "&foo=1"))).hasSize(6);
    }

    // ================================================================ transaction

    @Test
    void runsInOneReadOnlyRepeatableReadTransaction() {
        List<String> states = new ArrayList<>();
        beforeCount = () -> states.add(jdbc.queryForObject(TX_STATE, String.class));

        assertThat(ok(get(customerToken, branch, medical, DAY.toString()))).hasSize(6);

        assertThat(states).hasSize(1);
        assertThat(states.get(0)).startsWith("repeatable read|on|");
    }

    @Test
    void everyReadSeesOneSnapshot() {
        book(branch, medical, "MEDICAL", DAY, "08:00", "BOOKED");
        List<Boolean> injected = new ArrayList<>();
        beforeCount = () -> {
            beforeCount = null;
            autocommit("INSERT INTO appointments (code, customer_id, pet_id, branch_id, service_id, service_group, "
                    + "slot_date, slot_start, status, channel, booked_by) SELECT 'LH-IT-SNAP' || id, customer_id, "
                    + "pet_id, branch_id, service_id, service_group, slot_date, TIME '08:30', status, channel, booked_by "
                    + "FROM appointments WHERE branch_id = " + branch + " AND slot_start = TIME '08:00'");
            autocommit("INSERT INTO slot_quotas (branch_id, service_group, slot_date, slot_start, quota, updated_by) "
                    + "VALUES (" + branch + ", 'MEDICAL', DATE '" + DAY + "', TIME '08:30', 7, " + receptionist + ")");
            injected.add(true);
        };

        List<Map<String, Object>> during = ok(get(customerToken, branch, medical, DAY.toString()));

        assertThat(injected).hasSize(1);
        assertThat(during.get(1)).as("commit sau câu đọc đầu tiên không lọt vào kết quả")
                .isEqualTo(slot("08:30", "09:00", 1, 0, "AVAILABLE"));
        List<Map<String, Object>> after = ok(get(customerToken, branch, medical, DAY.toString()));
        assertThat(after.get(1)).isEqualTo(slot("08:30", "09:00", 7, 1, "AVAILABLE"));
    }

    @Test
    void failedQueryIs500AndReleasesACleanConnection() throws Exception {
        failCount = new DataAccessResourceFailureException("IT: DB tạm mất kết nối");

        assertError(get(customerToken, branch, medical, DAY.toString()), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", null);

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(ok(get(customerToken, branch, medical, DAY.toString()))).hasSize(6);
        assertPoolConnectionsAreReadCommittedReadWrite(hikari);
    }

    @Test
    void writesNothing() {
        book(branch, medical, "MEDICAL", DAY, "08:00", "BOOKED");
        data.slotQuota(branch, "MEDICAL", DAY, LocalTime.of(9, 0), 3, receptionist);
        Map<String, Object> before = fingerprint();

        for (int i = 0; i < 3; i++) {
            ok(get(customerToken, branch, medical, DAY.toString()));
            ok(get(receptionistToken, branch, medical, TODAY.toString()));
            get(customerToken, branch, 999_999_999L, DAY.toString());
        }

        assertThat(fingerprint()).isEqualTo(before);
    }

    // ================================================================ index

    @Test
    void countQueryCanUseThePartialQuotaIndex() throws Exception {
        String sql = AppointmentRepository.class
                .getMethod("countActiveBySlot", Long.class, String.class, LocalDate.class)
                .getAnnotation(Query.class).value()
                .replace(":branchId", "?").replace(":serviceGroup", "?").replace(":slotDate", "?");

        String plan = tx.execute(status -> {
            status.setRollbackOnly();
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, branch, "MEDICAL", DAY));
        });

        assertThat(plan).contains("ix_appointments_quota");
    }

    // ================================================================ helpers

    private void book(long branchId, long serviceId, String group, LocalDate date, String start, String status) {
        long profile = data.counterCustomer();
        data.appointment(profile, data.pet(profile), branchId, serviceId, group, date, LocalTime.parse(start), status,
                receptionist);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AvailableSlotsIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> get(String token, long branchId, long serviceId, String date) {
        return raw(token, "/api/branches/" + branchId + "/available-slots?serviceId=" + serviceId + "&date=" + date);
    }

    private ResponseEntity<Map<String, Object>> raw(String token, String pathAndQuery) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(URI.create(http.getRootUri() + pathAndQuery), HttpMethod.GET, new HttpEntity<>(headers),
                MAP);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        assertThat(response.getBody().get("data")).as("mảng, không null").isInstanceOf(List.class);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static List<String> starts(List<Map<String, Object>> slots) {
        return slots.stream().map(slot -> (String) slot.get("slotStart")).toList();
    }

    private static Map<String, Object> slot(String start, String end, int quota, int booked, String status) {
        return Map.of("slotStart", start, "slotEnd", end, "quota", quota, "booked", booked, "status", status);
    }

    private static Map<String, Object> withoutVolatile(Map<String, Object> body) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove("timestamp");
        copy.remove("traceId");
        return copy;
    }

    private long audits(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class,
                accountId);
    }

    private Map<String, Object> fingerprint() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM appointments) AS appointments,
                       (SELECT max(xmin::text::bigint) FROM appointments) AS appointments_xmin,
                       (SELECT max(xmin::text::bigint) FROM branches) AS branches_xmin,
                       (SELECT count(*) FROM slot_quotas) AS slot_quotas,
                       (SELECT max(xmin::text::bigint) FROM slot_quotas) AS slot_quotas_xmin,
                       (SELECT last_value FROM appointment_code_seq) AS code_seq,
                       (SELECT count(*) FROM audit_logs) AS audits""");
    }

    private void autocommit(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertPoolConnectionsAreReadCommittedReadWrite(HikariDataSource hikari) throws Exception {
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                borrowed.add(hikari.getConnection());
            }
            for (Connection connection : borrowed) {
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

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status, String errorCode,
            String message) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
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
