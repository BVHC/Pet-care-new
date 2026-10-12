package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.AccountLockedEvent;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.OrderQueryApi.UnpaidOrderRef;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;

/**
 * T14 phần 5 — {@code GET /api/accounts} (#28), {@code POST /api/accounts/{id}/lock} (#29, Tài khoản#7),
 * {@code POST /api/accounts/{id}/unlock} (#30, Tài khoản#8) qua HTTP trên Postgres 17 thật (BR-QT-04, 11, 12, 15,
 * BR-TK-11, 12, BR-TN-06, 08; 06 §8 Q2; docs/adr/0035). Sáu query API của module chưa cài là {@code @MockitoBean}
 * (bản placeholder thật ở {@code StaffTransferPlaceholderIT}); mọi thứ khác thật — đăng nhập, đăng xuất, quên / đặt lại
 * mật khẩu, vô hiệu hóa, điều chuyển, đổi chức vụ, kích hoạt chi nhánh, giao IN_APP. {@link EventProbe} nghe
 * {@link AccountLockedEvent} như listener của visit sau này (T26, nợ D017). Mỗi test tự tạo chi nhánh / tài khoản riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AccountLockIT.EventProbe.class})
class AccountLockIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Petcare123";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> SUMMARY_KEYS = Set.of("id", "email", "role", "status", "isLocked",
            "mustChangePassword");
    private static final Set<String> WORK_KEYS = Set.of("type", "entityId", "branchId", "needsReassign");
    private static final String FOLLOW_UP = "ACCOUNT_LOCKED_FOLLOW_UP_APP";
    private static final String LAST = "LAST_BRANCH_MANAGER_LOCKED_APP";
    private static final String WAITING_ON_ANY_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND datname = current_database()
            """;
    private static final String WAITING_ON_BRANCH_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from branches%for no key update%'
            """;

    /** Ghi lại mọi {@link AccountLockedEvent}: trong transaction không, thấy cờ khóa chưa; có thể bắt ném lỗi. */
    @TestConfiguration
    static class EventProbe {

        record Observed(AccountLockedEvent event, boolean inTransaction, boolean sawLocked) {}

        static final Map<Long, Observed> SEEN = new ConcurrentHashMap<>();
        static final Set<Long> FAIL_FOR = ConcurrentHashMap.newKeySet();

        @Autowired
        private JdbcTemplate jdbc;

        @EventListener
        public void on(AccountLockedEvent event) {
            Boolean locked = jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class,
                    event.accountId());
            SEEN.put(event.accountId(), new Observed(event,
                    TransactionSynchronizationManager.isActualTransactionActive(), Boolean.TRUE.equals(locked)));
            if (FAIL_FOR.contains(event.accountId())) {
                throw new IllegalStateException("IT: listener của AccountLockedEvent lỗi");
            }
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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InAppNotificationDeliveryService inAppDelivery;

    @MockitoBean
    private VisitQueryApi visitQueries;

    @MockitoBean
    private CashierShiftQueryApi cashierShifts;

    @MockitoBean
    private CustomerQueryApi customers;

    @MockitoBean
    private AppointmentQueryApi appointments;

    @MockitoBean
    private BoardingQueryApi boarding;

    @MockitoBean
    private OrderQueryApi orders;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long branchB;
    private long admin;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch("ACTIVE");
        branchB = branch("ACTIVE");
        admin = staff("ADMIN", null);
        doReturn(List.of()).when(visitQueries).findUnfinishedVisitsAssignedTo(anyLong());
        doReturn(Optional.empty()).when(cashierShifts).findOpenShift(anyLong());
        doReturn(List.of()).when(appointments).findBookedByCustomer(anyLong());
        doReturn(List.of()).when(boarding).findActiveStaysByCustomer(anyLong());
        doReturn(List.of()).when(orders).findUnpaidOrdersByCustomer(anyLong());
    }

    // ================================================================ #29 khóa nhân viên — thành công

    @Test
    void lockVetReturnsContractShapeRevokesEverySessionNotifiesManagersAuditsAndPublishes() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken1 = token(vet);
        String vetToken2 = token(vet);
        jdbc.update("UPDATE accounts SET last_seen_at = now() WHERE id = ?", vet);
        long manager1 = staff("BRANCH_MANAGER", branchA);
        long manager2 = staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchB);
        doReturn(List.of(new AssignedVisit(902L, branchA, VisitStatus.WAITING),
                new AssignedVisit(901L, branchA, VisitStatus.IN_PROGRESS)))
                .when(visitQueries).findUnfinishedVisitsAssignedTo(vet);

        ResponseEntity<Map<String, Object>> response = lock(token(admin), vet, "  Nghi bị chiếm tài khoản  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã khóa tài khoản").containsEntry("code", 200);
        Map<String, Object> data = map(ok(response));
        assertThat(data).containsOnlyKeys("account", "pendingWork");
        assertThat(map(data.get("account"))).containsOnlyKeys(SUMMARY_KEYS).containsEntry("id", (int) vet)
                .containsEntry("email", email(vet)).containsEntry("role", "VET").containsEntry("status", "ACTIVE")
                .containsEntry("isLocked", true).containsEntry("mustChangePassword", false);
        List<Map<String, Object>> work = list(data.get("pendingWork"));
        assertThat(work).allSatisfy(item -> assertThat(item).containsOnlyKeys(WORK_KEYS));
        assertThat(work).containsExactly(
                Map.of("type", "VISIT_IN_PROGRESS", "entityId", 901, "branchId", (int) branchA, "needsReassign", true),
                Map.of("type", "VISIT_WAITING", "entityId", 902, "branchId", (int) branchA, "needsReassign", false));

        assertError(get("/api/me", vetToken1), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(get("/api/me", vetToken2), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertLockedInvariant(vet);
        assertThat(text("SELECT locked_reason FROM accounts WHERE id = ?", vet)).isEqualTo("Nghi bị chiếm tài khoản");

        List<Map<String, Object>> notices = notices(vet);
        assertThat(notices).extracting(row -> number(row.get("recipient_account_id")))
                .containsExactly(manager1, manager2);
        for (Map<String, Object> notice : notices) {
            assertThat(notice).containsEntry("template_code", FOLLOW_UP).containsEntry("channel", "IN_APP")
                    .containsEntry("recipient_email", null);
            Map<String, Object> payload = payload(notice);
            assertPayloadMatchesTemplate(FOLLOW_UP, payload);
            assertThat(payload).containsEntry("email_tai_khoan", email(vet)).containsEntry("vai_tro", "Bác sĩ thú y")
                    .containsEntry("ly_do", "Nghi bị chiếm tài khoản")
                    .containsEntry("viec_do_dang", "- Lượt tiếp nhận #901: đang thực hiện, cần gán lại\n"
                            + "- Lượt tiếp nhận #902: đang chờ trong hàng đợi");
        }

        List<Map<String, Object>> audits = audits(admin, "ACCOUNT_LOCKED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts").containsEntry("entity_id", vet)
                .containsEntry("reason", "Nghi bị chiếm tài khoản")
                .containsEntry("before_data", "{\"status\": \"ACTIVE\", \"isLocked\": false}")
                .containsEntry("after_data", "{\"status\": \"ACTIVE\", \"isLocked\": true}");

        EventProbe.Observed observed = EventProbe.SEEN.get(vet);
        assertThat(observed.event()).isEqualTo(new AccountLockedEvent(vet, Role.VET, branchA,
                "Nghi bị chiếm tài khoản", admin));
        assertThat(observed.inTransaction()).as("sự kiện đồng bộ trong transaction khóa").isTrue();
        assertThat(observed.sawLocked()).as("listener thấy cờ khóa đã flush, chưa commit").isTrue();
        assertNotLocked(vet, branchA);
    }

    /** IN_APP được giao thật thành dòng {@code notifications} (ADR-0014): type = mã bỏ _APP, title = subject. */
    @Test
    void lockNoticeIsDeliveredAsInAppNotification() {
        long vet = staff("VET", branchA);
        long manager = staff("BRANCH_MANAGER", branchA);
        ok(lock(token(admin), vet, "Vi phạm"));
        long outboxId = number(notices(vet).get(0).get("id"));
        // Luồng IN_APP lấy theo (next_attempt_at, id): đưa dòng này lên đầu để không phụ thuộc tồn đọng của test khác.
        jdbc.update("UPDATE notification_outbox SET next_attempt_at = TIMESTAMPTZ '2000-01-01 00:00Z' WHERE id = ?",
                outboxId);

        inAppDelivery.deliverNext(Instant.now());

        assertThat(text("SELECT status FROM notification_outbox WHERE id = ?", outboxId)).isEqualTo("SENT");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT type, title, body FROM notifications WHERE account_id = ? ORDER BY id DESC LIMIT 1", manager);
        assertThat(row).containsEntry("type", "ACCOUNT_LOCKED_FOLLOW_UP")
                .containsEntry("title", "Tài khoản đã bị khóa — cần xử lý việc dở dang");
        assertThat((String) row.get("body")).contains(email(vet)).contains("Lý do: Vi phạm").contains("Không có");
    }

    /** U2: không có việc dở vẫn báo; chi nhánh không còn quản lý hoạt động → mọi SUPER_MANAGER (BR-TN-08). */
    @Test
    void receptionistWithoutManagerNotifiesEverySuperManager() {
        long receptionist = staff("RECEPTIONIST", branchA);
        long lockedManager = staff("BRANCH_MANAGER", branchA);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'trước' WHERE id = ?", lockedManager);
        doReturn(Optional.of(new OpenShift(777L, branchA))).when(cashierShifts).findOpenShift(receptionist);
        List<Long> superManagers = activeSuperManagers();

        Map<String, Object> data = map(ok(lock(token(admin), receptionist, "Vi phạm")));

        assertThat(list(data.get("pendingWork"))).containsExactly(Map.of("type", "CASHIER_SHIFT_OPEN", "entityId", 777,
                "branchId", (int) branchA, "needsReassign", false));
        assertThat(notices(receptionist)).extracting(row -> number(row.get("recipient_account_id")))
                .containsExactlyElementsOf(superManagers).doesNotContain(lockedManager);
    }

    /** BR-TK-11, 12, BR-QT-12: khóa chặn đăng nhập, quên mật khẩu, vô hiệu hóa; mở khóa khôi phục đúng như trước. */
    @Test
    void lockedAccountCannotLoginResetOrBeDisabledAndUnlockRestoresIt() {
        long vet = staffWithPassword("VET", branchA);
        long superManager = staff("SUPER_MANAGER", null);
        ok(lock(token(admin), vet, "Vi phạm"));

        assertRule(login(email(vet), PASSWORD), "BR-TK-11");
        assertThat(post("/api/auth/password/forgot", null, Map.of("email", email(vet))).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_PASSWORD_RESET'""", Long.class, email(vet)))
                .as("BR-TK-12: không gửi OTP").isZero();
        assertRule(post("/api/staff/" + vet + "/disable", token(superManager), null), "BR-QT-12");
        assertRule(post("/api/staff/" + vet + "/reactivate", token(superManager), null), "BR-QT-10");

        Map<String, Object> unlocked = map(ok(unlock(token(admin), vet, "Đã xác minh")));

        assertThat(unlocked).containsOnlyKeys(SUMMARY_KEYS).containsEntry("isLocked", false)
                .containsEntry("status", "ACTIVE");
        assertThat(ok(login(email(vet), PASSWORD))).isNotNull();
        assertThat(text("SELECT locked_reason FROM accounts WHERE id = ?", vet)).as("lý do gần nhất giữ lại")
                .isEqualTo("Vi phạm");
        assertThat(audits(admin, "ACCOUNT_UNLOCKED")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("reason", "Đã xác minh")
                        .containsEntry("before_data", "{\"status\": \"ACTIVE\", \"isLocked\": true}")
                        .containsEntry("after_data", "{\"status\": \"ACTIVE\", \"isLocked\": false}"));
    }

    /** BR-QT-12: tài khoản DISABLED khóa được; mở khóa trả về DISABLED. */
    @Test
    void disabledStaffLockedThenUnlockedStaysDisabled() {
        long caretaker = staff("CARETAKER", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", caretaker);

        assertThat(map(map(ok(lock(token(admin), caretaker, "Vi phạm"))).get("account")))
                .containsEntry("status", "DISABLED").containsEntry("isLocked", true);
        assertThat(map(ok(unlock(token(admin), caretaker, "Xong")))).containsEntry("status", "DISABLED")
                .containsEntry("isLocked", false);
        assertThat(text("SELECT status FROM accounts WHERE id = ?", caretaker)).isEqualTo("DISABLED");
    }

    // ================================================================ #29 khóa BRANCH_MANAGER (BR-QT-04, U3, U4)

    @Test
    void lastManagerOfActiveBranchSendsOnlyLastNoticeAndBranchStaysActive() throws Exception {
        long manager = staff("BRANCH_MANAGER", branchA);
        List<Long> superManagers = activeSuperManagers();

        ok(lock(token(admin), manager, "Vi phạm"));

        List<Map<String, Object>> notices = notices(manager);
        assertThat(notices).extracting(row -> number(row.get("recipient_account_id")))
                .containsExactlyElementsOf(superManagers);
        assertThat(notices).allSatisfy(row -> assertThat(row).containsEntry("template_code", LAST));
        Map<String, Object> payload = payload(notices.get(0));
        assertPayloadMatchesTemplate(LAST, payload);
        assertThat(payload).containsEntry("ten_chi_nhanh", text("SELECT name FROM branches WHERE id = ?", branchA))
                .containsEntry("vai_tro", "Quản lý chi nhánh");
        assertThat(text("SELECT status FROM branches WHERE id = ?", branchA)).isEqualTo("ACTIVE");
        assertNotLocked(manager, branchA);
    }

    @Test
    void managerWithAnotherActiveManagerSendsFollowUpToSuperManagers() {
        long manager = staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchA);

        ok(lock(token(admin), manager, "Vi phạm"));

        assertThat(notices(manager)).isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("template_code", FOLLOW_UP));
    }

    /** BR-QT-04 ngoại lệ: quản lý bị khóa vẫn được đếm — chi nhánh DRAFT kích hoạt được (docs/adr/0024). */
    @Test
    void lockedManagerOfDraftBranchStillCountsForActivation() {
        long draft = branch("DRAFT");
        hours(draft);
        long manager = staff("BRANCH_MANAGER", draft);
        long superManager = staff("SUPER_MANAGER", null);

        ok(lock(token(admin), manager, "Vi phạm"));

        assertThat(notices(manager)).allSatisfy(row -> assertThat(row).containsEntry("template_code", FOLLOW_UP));
        ok(post("/api/branches/" + draft + "/activate", token(superManager), null));
        assertThat(text("SELECT status FROM branches WHERE id = ?", draft)).isEqualTo("ACTIVE");
    }

    @Test
    void lockedVetLeavesPublicVets() {
        long vet = staff("VET", branchA);
        assertThat(publicVetIds()).contains(vet);

        ok(lock(token(admin), vet, "Vi phạm"));

        assertThat(publicVetIds()).doesNotContain(vet);
        ok(unlock(token(admin), vet, "Xong"));
        assertThat(publicVetIds()).contains(vet);
    }

    // ================================================================ #29 khóa khách (06 §8 Q2)

    @Test
    void customerWorkIsSentToReceptionistsOfEachBranchOnlyWithThatBranchsItems() {
        long customer = customerAccount("ACTIVE");
        long customerId = givenCustomerProfile(customer);
        long receptionistA1 = staff("RECEPTIONIST", branchA);
        long receptionistA2 = staff("RECEPTIONIST", branchA);
        long receptionistB = staff("RECEPTIONIST", branchB);
        staff("VET", branchA);
        doReturn(List.of(new BookedSlot(401L, "LH-1", branchA, 1L, customerId, LocalDate.of(2026, 10, 20),
                LocalTime.of(9, 0)))).when(appointments).findBookedByCustomer(customerId);
        doReturn(List.of(new StaySummary(501L, branchB, customerId, 1L, StayStatus.OVERDUE, "0901111111")))
                .when(boarding).findActiveStaysByCustomer(customerId);
        doReturn(List.of(new UnpaidOrderRef(601L, branchA))).when(orders).findUnpaidOrdersByCustomer(customerId);

        Map<String, Object> data = map(ok(lock(token(admin), customer, "Đặt lịch ảo")));

        assertThat(list(data.get("pendingWork"))).extracting(item -> item.get("type"))
                .containsExactly("APPOINTMENT_BOOKED", "BOARDING_ACTIVE", "ORDER_UNPAID");
        List<Map<String, Object>> notices = notices(customer);
        assertThat(notices).extracting(row -> number(row.get("recipient_account_id")))
                .containsExactly(receptionistA1, receptionistA2, receptionistB);
        assertThat(payload(notices.get(0))).containsEntry("ho_ten", "Khách " + tag)
                .containsEntry("vai_tro", "Khách hàng")
                .containsEntry("viec_do_dang", "- Lịch hẹn #401: đã đặt\n- Order #601: chưa thanh toán");
        assertThat(payload(notices.get(2))).containsEntry("viec_do_dang", "- Đặt chỗ lưu trú #501: đang hiệu lực");
        assertThat(EventProbe.SEEN.get(customer).event()).isEqualTo(new AccountLockedEvent(customer, Role.CUSTOMER,
                null, "Đặt lịch ảo", admin));
    }

    /** Q2: không có việc dở → không ai nhận; tài khoản PENDING cũng khóa được. */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"ACTIVE", "PENDING"})
    void customerWithoutPendingWorkNotifiesNobody(String status) {
        long customer = customerAccount(status);
        givenCustomerProfile(customer);

        Map<String, Object> data = map(ok(lock(token(admin), customer, "Spam")));

        assertThat(list(data.get("pendingWork"))).isEmpty();
        assertThat(map(data.get("account"))).containsEntry("status", status).containsEntry("role", "CUSTOMER");
        assertThat(notices(customer)).isEmpty();
        assertLockedInvariant(customer);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUPER_MANAGER", "ADMIN"})
    void chainLevelAccountHasNoPendingWorkAndNoNotice(String role) {
        long target = staff(role, null);

        Map<String, Object> data = map(ok(lock(token(admin), target, "Sự cố bảo mật")));

        assertThat(list(data.get("pendingWork"))).isEmpty();
        assertThat(notices(target)).isEmpty();
        assertLockedInvariant(target);
    }

    // ================================================================ lỗi, validate, quyền

    @Test
    void selfLockIsBrQt11AndChangesNothing() {
        String adminToken = token(admin);

        assertRule(lock(adminToken, admin, "Thử"), "BR-QT-11");

        assertThat(get("/api/me", adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, admin)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER", "CUSTOMER"})
    void nonAdminIs403ForEveryEndpointWithoutAudit(String role) {
        long actor = "CUSTOMER".equals(role) ? customerAccount("ACTIVE") : staff(role, branchA);
        long target = staff("VET", branchA);
        String actorToken = token(actor);

        assertError(lock(actorToken, target, "Thử"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(unlock(actorToken, target, "Thử"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(get("/api/accounts", actorToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class,
                actor)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, target)).isFalse();
    }

    @Test
    void withoutTokenIs401AndAdminWhoMustChangePasswordIsBlocked() {
        long vet = staff("VET", branchA);
        assertError(lock(null, vet, "Thử"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", admin);
        assertRule(lock(token(admin), vet, "Thử"), "BR-TK-17");
        assertRule(get("/api/accounts", token(admin)), "BR-TK-17");
    }

    @Test
    void missingAccountIs404AndStateConflictsAre409() {
        assertError(lock(token(admin), Long.MAX_VALUE, "Thử"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(unlock(token(admin), Long.MAX_VALUE, "Thử"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        long vet = staff("VET", branchA);
        assertError(unlock(token(admin), vet, "Thử"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        ok(lock(token(admin), vet, "Lần đầu"));
        assertError(lock(token(admin), vet, "Lần hai"), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(text("SELECT locked_reason FROM accounts WHERE id = ?", vet)).isEqualTo("Lần đầu");
        assertThat(audits(admin, "ACCOUNT_LOCKED")).hasSize(1);
    }

    @ParameterizedTest(name = "body={0}")
    @CsvSource(delimiter = '|', value = {
            "{}                    | VALIDATION_FAILED",
            "{\"reason\": null}    | VALIDATION_FAILED",
            "{\"reason\": \"   \"} | VALIDATION_FAILED",
            "{\"reason\": 5        | MALFORMED_REQUEST",
            "''                    | MALFORMED_REQUEST"})
    void invalidBodyIs400AndChangesNothing(String body, String errorCode) {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        String json = "''".equals(body) ? "" : body;

        assertError(raw("/api/accounts/" + vet + "/lock", token(admin), json), HttpStatus.BAD_REQUEST, errorCode);
        assertError(raw("/api/accounts/" + vet + "/unlock", token(admin), json), HttpStatus.BAD_REQUEST, errorCode);

        assertThat(get("/api/me", vetToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vet)).isFalse();
    }

    @Test
    void reasonLengthAndBadPathAreValidated() {
        long vet = staff("VET", branchA);

        ResponseEntity<Map<String, Object>> tooLong = lock(token(admin), vet, "a".repeat(501));
        assertError(tooLong, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) tooLong.getBody().get("message")).contains("reason").contains("Lý do tối đa 500 ký tự");
        assertError(raw("/api/accounts/abc/lock", token(admin), "{\"reason\":\"x\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");

        ok(lock(token(admin), vet, "a".repeat(500)));
    }

    // ================================================================ commit / rollback (T3–T5)

    @Test
    void outboxFailureRollsBackLockIncludingSessionRevoke() {
        long vet = staff("VET", branchA);
        staff("BRANCH_MANAGER", branchA);
        String vetToken = token(vet);

        withTrigger("notification_outbox", "INSERT", "NEW.payload->>'email_tai_khoan' = '" + email(vet) + "'",
                () -> assertError(lock(token(admin), vet, "Vi phạm"), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertNothingChanged(vet, vetToken);
    }

    @Test
    void auditFailureRollsBackLock() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);

        withTrigger("audit_logs", "INSERT", "NEW.action = 'ACCOUNT_LOCKED' AND NEW.entity_id = " + vet,
                () -> assertError(lock(token(admin), vet, "Vi phạm"), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertNothingChanged(vet, vetToken);
    }

    /** UPDATE accounts đã flush trước UPDATE sessions; lỗi ở sessions rollback cả cờ khóa. */
    @Test
    void sessionRevokeFailureRollsBackLockAlreadyFlushed() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);

        withTrigger("sessions", "UPDATE", "NEW.account_id = " + vet,
                () -> assertError(lock(token(admin), vet, "Vi phạm"), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertNothingChanged(vet, vetToken);
    }

    @Test
    void listenerFailureRollsBackLock() {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        EventProbe.FAIL_FOR.add(vet);
        try {
            assertError(lock(token(admin), vet, "Vi phạm"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        } finally {
            EventProbe.FAIL_FOR.remove(vet);
        }

        assertNothingChanged(vet, vetToken);
    }

    @Test
    void auditFailureRollsBackUnlock() {
        long vet = staff("VET", branchA);
        ok(lock(token(admin), vet, "Vi phạm"));

        withTrigger("audit_logs", "INSERT", "NEW.action = 'ACCOUNT_UNLOCKED' AND NEW.entity_id = " + vet,
                () -> assertError(unlock(token(admin), vet, "Xong"), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vet)).isTrue();
    }

    // ================================================================ khóa + race (L2, L3, L5)

    @Test
    void doubleLockIsOneSuccessAndOneConflict() throws Exception {
        long vet = staff("VET", branchA);
        staff("BRANCH_MANAGER", branchA);
        String adminToken = token(admin);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet,
                List.of(() -> lock(adminToken, vet, "Một"), () -> lock(adminToken, vet, "Hai")));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(audits(admin, "ACCOUNT_LOCKED")).hasSize(1);
        assertThat(notices(vet)).hasSize(1);
        assertLockedInvariant(vet);
    }

    /** I1: đăng nhập đi trước → phiên vừa mở bị hủy; khóa đi trước → đăng nhập 400 BR-TK-11. */
    @Test
    void loginRacingLockNeverLeavesLiveSession() throws Exception {
        String adminToken = token(admin);
        long first = staffWithPassword("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> loginFirst = whileAccountLockedMany(first,
                List.of(() -> login(email(first), PASSWORD), () -> lock(adminToken, first, "Vi phạm")));
        String issued = (String) map(ok(loginFirst.get(0))).get("accessToken");
        ok(loginFirst.get(1));
        assertError(get("/api/me", issued), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertLockedInvariant(first);

        long second = staffWithPassword("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> lockFirst = whileAccountLockedMany(second,
                List.of(() -> lock(adminToken, second, "Vi phạm"), () -> login(email(second), PASSWORD)));
        ok(lockFirst.get(0));
        assertRule(lockFirst.get(1), "BR-TK-11");
        assertLockedInvariant(second);
    }

    /** I4 (U4): hai ADMIN khóa hai quản lý cuối của cùng chi nhánh → đúng một lần khóa gửi LAST. */
    @Test
    void concurrentLockOfLastTwoManagersSendsExactlyOneLast() throws Exception {
        long manager1 = staff("BRANCH_MANAGER", branchA);
        long manager2 = staff("BRANCH_MANAGER", branchA);
        String adminToken = token(admin);
        String admin2Token = token(staff("ADMIN", null));

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA), List.of(
                () -> lock(adminToken, manager1, "Vi phạm"), () -> lock(admin2Token, manager2, "Vi phạm")));

        ok(results.get(0));
        ok(results.get(1));
        assertThat(notices(manager1)).isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("template_code", FOLLOW_UP));
        assertThat(notices(manager2)).isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("template_code", LAST));
        assertNotLocked(manager2, branchA);
    }

    /** L3: điều chuyển BRANCH_MANAGER xong trong lúc khóa chờ chi nhánh cũ → 409, không ghi gì. */
    @Test
    void lockRacingTransferOfManagerIs409AndWritesNothing() throws Exception {
        long manager = staff("BRANCH_MANAGER", branchA);
        staff("BRANCH_MANAGER", branchA);
        long superManager = staff("SUPER_MANAGER", null);
        String smToken = token(superManager);
        String adminToken = token(admin);

        List<ResponseEntity<Map<String, Object>>> results = whileBranchesLocked(List.of(branchA), List.of(
                () -> post("/api/staff/" + manager + "/transfer", smToken, Map.of("branchId", branchB)),
                () -> lock(adminToken, manager, "Vi phạm")));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, manager))
                .isFalse();
        assertThat(audits(admin, "ACCOUNT_LOCKED")).isEmpty();
        assertNotLocked(manager, branchA, branchB);
    }

    /** L3: VET được đổi chức vụ lên BRANCH_MANAGER trong lúc khóa chờ (khóa không giữ chi nhánh) → 409. */
    @Test
    void lockRacingChangeRoleToManagerIs409() throws Exception {
        long vet = staff("VET", branchA);
        long superManager = staff("SUPER_MANAGER", null);
        String smToken = token(superManager);
        String adminToken = token(admin);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> post("/api/staff/" + vet + "/change-role", smToken, Map.of("role", "BRANCH_MANAGER")),
                () -> lock(adminToken, vet, "Vi phạm")));

        ok(results.get(0));
        assertError(results.get(1), HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT is_locked FROM accounts WHERE id = ?", Boolean.class, vet)).isFalse();
    }

    /** I7: điều chuyển VET sang chi nhánh B commit trước → thông báo gửi quản lý của B. */
    @Test
    void lockRacingTransferNotifiesNewBranchManagers() throws Exception {
        long vet = staff("VET", branchA);
        staff("BRANCH_MANAGER", branchA);
        long managerB = staff("BRANCH_MANAGER", branchB);
        String smToken = token(staff("SUPER_MANAGER", null));
        String adminToken = token(admin);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> post("/api/staff/" + vet + "/transfer", smToken, Map.of("branchId", branchB)),
                () -> lock(adminToken, vet, "Vi phạm")));

        ok(results.get(0));
        ok(results.get(1));
        assertThat(notices(vet)).extracting(row -> number(row.get("recipient_account_id"))).containsExactly(managerB);
        assertThat(EventProbe.SEEN.get(vet).event().branchId()).isEqualTo(branchB);
    }

    /** L3: ST02 xóa tài khoản PENDING trong lúc khóa chờ dòng → 404, không phải 500. */
    @Test
    void lockRacingPendingCleanupIs404() throws Exception {
        long pending = customerAccount("PENDING");
        String adminToken = token(admin);

        ResponseEntity<Map<String, Object>> result = whileAccountLockedThen(pending,
                () -> lock(adminToken, pending, "Spam"),
                holder -> jdbc.update("DELETE FROM accounts WHERE id = ?", pending));

        assertError(result, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(audits(admin, "ACCOUNT_LOCKED")).isEmpty();
    }

    /** I2: đặt lại mật khẩu đã qua bước so mã, chờ khóa dòng sau lệnh khóa → kiểm lại dưới khóa → BR-TK-05. */
    @Test
    void resetPasswordRacingLockIsRejected() throws Exception {
        long vet = staffWithPassword("VET", branchA);
        assertThat(post("/api/auth/password/forgot", null, Map.of("email", email(vet))).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String code = jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_PASSWORD_RESET' ORDER BY id DESC LIMIT 1""",
                String.class, email(vet));
        String adminToken = token(admin);
        String hash = text("SELECT password_hash FROM accounts WHERE id = ?", vet);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> lock(adminToken, vet, "Vi phạm"),
                () -> post("/api/auth/password/reset", null,
                        Map.of("email", email(vet), "code", code, "newPassword", "Moi12345abc"))));

        ok(results.get(0));
        assertRule(results.get(1), "BR-TK-05");
        assertThat(text("SELECT password_hash FROM accounts WHERE id = ?", vet)).isEqualTo(hash);
    }

    /** BR-QT-12 dưới khóa dòng, cả hai thứ tự; không deadlock dù vô hiệu hóa khóa chi nhánh trước. */
    @Test
    void lockAndDisableBothOrders() throws Exception {
        String smToken = token(staff("SUPER_MANAGER", null));
        String adminToken = token(admin);

        long first = staff("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> disableFirst = whileAccountLockedMany(first, List.of(
                () -> post("/api/staff/" + first + "/disable", smToken, null),
                () -> lock(adminToken, first, "Vi phạm")));
        ok(disableFirst.get(0));
        assertThat(map(map(ok(disableFirst.get(1))).get("account"))).containsEntry("status", "DISABLED")
                .containsEntry("isLocked", true);

        long second = staff("VET", branchA);
        List<ResponseEntity<Map<String, Object>>> lockFirst = whileAccountLockedMany(second, List.of(
                () -> lock(adminToken, second, "Vi phạm"),
                () -> post("/api/staff/" + second + "/disable", smToken, null)));
        ok(lockFirst.get(0));
        assertRule(lockFirst.get(1), "BR-QT-12");
        assertNotLocked(second, branchA);
    }

    /** {@code accounts → sessions} ở cả hai luồng → không deadlock; {@code last_seen_at} có giá trị để đăng xuất chờ. */
    @Test
    void lockAndLogoutDoNotDeadlock() throws Exception {
        long vet = staff("VET", branchA);
        String vetToken = token(vet);
        String adminToken = token(admin);
        jdbc.update("UPDATE accounts SET last_seen_at = now() WHERE id = ?", vet);

        List<ResponseEntity<Map<String, Object>>> results = whileAccountLockedMany(vet, List.of(
                () -> post("/api/auth/logout", vetToken, null), () -> lock(adminToken, vet, "Vi phạm")));

        assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ok(results.get(1));
        assertLockedInvariant(vet);
    }

    // ================================================================ #28 tìm tài khoản

    @Test
    void listFiltersByEmailRoleAndLockAndKeepsContractShape() {
        long vet = staff("VET", branchA);
        long manager = staff("BRANCH_MANAGER", branchA);
        long customer = customerAccount("PENDING");
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'x' WHERE id = ?", manager);

        Map<String, Object> page = map(ok(get("/api/accounts?q=" + tag.toUpperCase() + "&size=50", token(admin))));

        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("page", 0).containsEntry("size", 50);
        List<Map<String, Object>> content = list(page.get("content"));
        assertThat(content).allSatisfy(row -> assertThat(row).containsOnlyKeys(SUMMARY_KEYS));
        assertThat(content).extracting(row -> number(row.get("id")))
                .as("tìm trong email, không phân biệt hoa thường, theo id").containsExactly(admin, vet, manager, customer);
        assertThat(content.get(3)).containsEntry("role", "CUSTOMER").containsEntry("status", "PENDING");

        assertThat(ids("/api/accounts?q=" + tag + "&role=VET")).containsExactly(vet);
        assertThat(ids("/api/accounts?q=" + tag + "&isLocked=true")).containsExactly(manager);
        assertThat(ids("/api/accounts?q=" + tag + "&isLocked=false&role=CUSTOMER")).containsExactly(customer);
        Map<String, Object> second = map(ok(get("/api/accounts?q=" + tag + "&page=1&size=2", token(admin))));
        assertThat(list(second.get("content"))).extracting(row -> number(row.get("id")))
                .containsExactly(manager, customer);
        assertThat(second).containsEntry("totalElements", 4).containsEntry("totalPages", 2);
    }

    @Test
    void listParametersAreValidated() {
        assertError(get("/api/accounts?role=ROOT", token(admin)), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get("/api/accounts?isLocked=maybe", token(admin)), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get("/api/accounts?q=" + "a".repeat(256), token(admin)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(ids("/api/accounts?q=" + tag + "%25_!")).as("ký tự LIKE được escape").isEmpty();
    }

    // ================================================================ helpers — dữ liệu

    private long branch(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, "AccountLockIT " + UUID.randomUUID(), status);
    }

    private void hours(long branchId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2026-10-01', ?, ?, ?)
                    """, branchId, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        }
    }

    private long staff(String role, Long branchId) {
        return staffWithHash(role, branchId, "hash");
    }

    private long staffWithPassword(String role, Long branchId) {
        return staffWithHash(role, branchId, passwordEncoder.encode(PASSWORD));
    }

    private long staffWithHash(String role, Long branchId, String hash) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), hash, role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag + " " + id, branchId);
        return id;
    }

    private long customerAccount(String status) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, 'hash', 'CUSTOMER', ?, CASE WHEN ? = 'PENDING' THEN now() + interval '1 day' END)
                RETURNING id
                """, Long.class, newEmail(), status, status);
    }

    /** Hồ sơ khách qua {@code CustomerQueryApi} (mock): id hồ sơ riêng theo tài khoản. */
    private long givenCustomerProfile(long accountId) {
        long customerId = 1_000_000L + accountId;
        doReturn(Optional.of(customerId)).when(customers).findCustomerIdByAccountId(accountId);
        doReturn(Optional.of(new CustomerContact(customerId, "Khách " + tag, "0901111111", email(accountId),
                accountId, false))).when(customers).findContact(customerId);
        return customerId;
    }

    private String newEmail() {
        return "lock-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    private String email(long accountId) {
        return text("SELECT email FROM accounts WHERE id = ?", accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AccountLockIT")).accessToken();
    }

    private String text(String sql, long id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private List<Long> activeSuperManagers() {
        return jdbc.queryForList("""
                SELECT id FROM accounts WHERE role = 'SUPER_MANAGER' AND status = 'ACTIVE' AND NOT is_locked
                ORDER BY id""", Long.class);
    }

    private List<Map<String, Object>> audits(long actorId, String action) {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, reason, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE actor_account_id = ? AND action = ? ORDER BY id""", actorId, action);
    }

    /** Thông báo của một lần khóa: lọc theo email người bị khóa trong payload, theo thứ tự ghi. */
    private List<Map<String, Object>> notices(long lockedId) {
        return jdbc.queryForList("""
                SELECT id, recipient_account_id, recipient_email, template_code, channel, payload::text AS payload
                FROM notification_outbox WHERE payload->>'email_tai_khoan' = ? ORDER BY id""", email(lockedId));
    }

    private Map<String, Object> payload(Map<String, Object> notice) {
        try {
            return objectMapper.readValue((String) notice.get("payload"), new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Khóa payload ⊆ {@code allowed_vars} và ⊇ {@code required_vars} của dòng mẫu V12 trong DB (BR-QT-14). */
    private void assertPayloadMatchesTemplate(String code, Map<String, Object> payload) throws Exception {
        Map<String, Object> template = jdbc.queryForMap("""
                SELECT allowed_vars::text AS allowed, required_vars::text AS required, channel
                FROM notification_templates WHERE code = ?""", code);
        List<String> allowed = objectMapper.readValue((String) template.get("allowed"), new TypeReference<>() {
        });
        List<String> required = objectMapper.readValue((String) template.get("required"), new TypeReference<>() {
        });
        assertThat(template).containsEntry("channel", "IN_APP");
        assertThat(allowed).containsAll(payload.keySet());
        assertThat(payload.keySet()).containsAll(required);
        assertThat(payload.values()).doesNotContainNull();
    }

    @SuppressWarnings("unchecked")
    private List<Long> publicVetIds() {
        return ((List<Map<String, Object>>) http.exchange("/api/public/vets", HttpMethod.GET, null, MAP).getBody()
                .get("data")).stream().map(row -> number(row.get("accountId"))).toList();
    }

    private List<Long> ids(String path) {
        return list(map(ok(get(path, token(admin)))).get("content")).stream().map(row -> number(row.get("id")))
                .toList();
    }

    /** I1: đã khóa ⇒ không còn phiên chưa hủy và offline. */
    private void assertLockedInvariant(long accountId) {
        assertThat(jdbc.queryForMap("SELECT is_locked, last_seen_at FROM accounts WHERE id = ?", accountId))
                .containsEntry("is_locked", true).containsEntry("last_seen_at", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId)).isZero();
    }

    /** Rollback trọn: chưa khóa, chưa lý do, phiên còn dùng được, không audit, không thông báo. */
    private void assertNothingChanged(long accountId, String token) {
        assertThat(jdbc.queryForMap("SELECT is_locked, locked_reason FROM accounts WHERE id = ?", accountId))
                .containsEntry("is_locked", false).containsEntry("locked_reason", null);
        assertThat(get("/api/me", token).getStatusCode()).as("UPDATE sessions cũng rollback").isEqualTo(HttpStatus.OK);
        assertThat(audits(admin, "ACCOUNT_LOCKED")).isEmpty();
        assertThat(notices(accountId)).isEmpty();
        assertNotLocked(accountId, branchA);
    }

    private void assertNotLocked(long accountId, long... branchIds) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, accountId);
            for (long branchId : branchIds) {
                jdbc.queryForObject("SELECT id FROM branches WHERE id = ? FOR UPDATE NOWAIT", Long.class, branchId);
            }
        });
    }

    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_lock_fail_" + suffix;
        String trigger = "trg_it_lock_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, operation, table, function));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    // ================================================================ helpers — đồng thời

    private List<ResponseEntity<Map<String, Object>>> whileBranchesLocked(Collection<Long> branchIds,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM branches WHERE id = ? FOR UPDATE", branchIds, WAITING_ON_BRANCH_LOCK,
                requests, holder -> {
                });
    }

    private List<ResponseEntity<Map<String, Object>>> whileAccountLockedMany(long accountId,
            List<Callable<ResponseEntity<Map<String, Object>>>> requests) throws Exception {
        return whileLocked("SELECT id FROM accounts WHERE id = ? FOR UPDATE", List.of(accountId), WAITING_ON_ANY_LOCK,
                requests, holder -> {
                });
    }

    /** Một request chờ khóa {@code accounts}; transaction giữ khóa chạy {@code beforeRelease} rồi mới commit. */
    private ResponseEntity<Map<String, Object>> whileAccountLockedThen(long accountId,
            Callable<ResponseEntity<Map<String, Object>>> request, Consumer<Long> beforeRelease) throws Exception {
        return whileLocked("SELECT id FROM accounts WHERE id = ? FOR UPDATE", List.of(accountId), WAITING_ON_ANY_LOCK,
                List.of(request), beforeRelease).get(0);
    }

    private List<ResponseEntity<Map<String, Object>>> whileLocked(String lockSql, Collection<Long> ids,
            String waitingSql, List<Callable<ResponseEntity<Map<String, Object>>>> requests,
            Consumer<Long> beforeRelease) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                for (Long id : ids) {
                    jdbc.queryForObject(lockSql, Long.class, id);
                }
                held.countDown();
                await(release);
                beforeRelease.accept(ids.iterator().next());
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            List<Future<ResponseEntity<Map<String, Object>>>> calls = new ArrayList<>();
            for (Callable<ResponseEntity<Map<String, Object>>> request : requests) {
                calls.add(pool.submit(request));
                awaitWaiting(waitingSql, calls.size());
            }
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                assertThat(call.isDone()).as("request phải chờ khóa").isFalse();
            }
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaiting(String sql, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(sql, Long.class) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("cần " + expected + " request chờ khóa; pg_stat_activity = "
                        + jdbc.queryForList("""
                        SELECT wait_event_type, state, query FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid()"""));
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================ helpers — HTTP

    private ResponseEntity<Map<String, Object>> lock(String token, long id, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        return post("/api/accounts/" + id + "/lock", token, body);
    }

    private ResponseEntity<Map<String, Object>> unlock(String token, long id, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        return post("/api/accounts/" + id + "/unlock", token, body);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return post("/api/auth/login", null, Map.of("email", email, "password", password));
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String path, String token, String body) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static Object ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp",
                        "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        assertThat((String) response.getBody().get("message")).isNotBlank();
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
    }
}
