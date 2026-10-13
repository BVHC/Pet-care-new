package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.SessionService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * T14 phần 1 — {@code GET/POST /api/staff}, {@code GET /api/staff/{id}}, {@code POST /api/staff/{id}/resend-initial-password}
 * (identity-v1 #15, #16, #17, #22; BR-QT-01, 02, 03, 07, 15, BR-TK-01, 17, Tài khoản#4; docs/adr/0029, 0030) qua HTTP
 * trên Postgres 17 thật, không mock module nào. Mỗi test tự tạo chi nhánh / tài khoản riêng (email UUID, họ tên mang
 * {@code tag} riêng để lọc danh sách), nên không phụ thuộc dữ liệu của test khác; audit tra theo actor / entity riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StaffAdminIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> STAFF_KEYS = Set.of("accountId", "email", "phone", "fullName", "role", "status",
            "isLocked", "branchId", "specialty", "bio", "mustChangePassword", "online");
    private static final String WAITING_ON_ACCOUNT_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
            """;

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

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private AccountRepository accountRepository;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long branchB;
    private long admin;
    private long superManager;
    private long managerA;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch("ACTIVE");
        branchB = branch("ACTIVE");
        admin = staff("ADMIN", null);
        superManager = staff("SUPER_MANAGER", null);
        managerA = staff("BRANCH_MANAGER", branchA);
    }

    // ================================================================ #16 tạo — thành công

    @Test
    void adminCreatesSuperManagerWithContractShapeRowsMailAndAudit() {
        String email = newEmail();

        ResponseEntity<Map<String, Object>> response = create(token(admin),
                body(email.toUpperCase(), "0901234567", "  Quản lý " + tag + "  ", "SUPER_MANAGER", null));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code")
                .containsEntry("message", "Đã tạo tài khoản nhân viên").containsEntry("code", 201);
        Map<String, Object> data = map(response.getBody().get("data"));
        assertThat(data.keySet()).isEqualTo(STAFF_KEYS);
        assertThat(data).containsEntry("email", email).containsEntry("phone", "0901234567")
                .containsEntry("fullName", "Quản lý " + tag).containsEntry("role", "SUPER_MANAGER")
                .containsEntry("status", "ACTIVE").containsEntry("isLocked", false).containsEntry("branchId", null)
                .containsEntry("specialty", null).containsEntry("bio", null)
                .containsEntry("mustChangePassword", true).containsEntry("online", false);
        long id = number(data.get("accountId"));

        assertThat(jdbc.queryForMap("""
                SELECT a.email, a.phone, a.role, a.status, a.is_locked, a.must_change_password, a.pending_expires_at,
                       sp.full_name, sp.branch_id, sp.avatar_url
                FROM accounts a JOIN staff_profiles sp ON sp.account_id = a.id WHERE a.id = ?""", id))
                .containsEntry("email", email).containsEntry("role", "SUPER_MANAGER").containsEntry("status", "ACTIVE")
                .containsEntry("is_locked", false).containsEntry("must_change_password", true)
                .containsEntry("pending_expires_at", null).containsEntry("branch_id", null)
                .containsEntry("avatar_url", null).containsEntry("full_name", "Quản lý " + tag);

        List<Map<String, Object>> mails = outbox(email);
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0)).containsEntry("template_code", "STAFF_TEMP_PASSWORD")
                .containsEntry("channel", "EMAIL").containsEntry("status", "PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(k, ',' ORDER BY k) FROM notification_outbox, jsonb_object_keys(payload) k
                WHERE recipient_email = ?""", String.class, email)).isEqualTo("email_dang_nhap,ho_ten,mat_khau_tam");
        String plain = tempPassword(email);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id))
                .satisfies(hash -> assertThat(passwordEncoder.matches(plain, hash)).isTrue());
        assertThat(response.getBody().toString()).doesNotContain(plain);

        List<Map<String, Object>> audits = audits(admin, "STAFF_CREATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts").containsEntry("entity_id", id)
                .containsEntry("before_data", null);
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(k, ',' ORDER BY k) FROM audit_logs, jsonb_object_keys(after_data) k
                WHERE actor_account_id = ? AND action = 'STAFF_CREATED'""", String.class, admin))
                .isEqualTo("branchId,email,fullName,phone,role,status");
        assertThat(String.valueOf(audits.get(0).get("after_data"))).doesNotContain(plain);
    }

    @Test
    void createResponseEqualsGetResponse() {
        ResponseEntity<Map<String, Object>> created = create(token(superManager),
                body(newEmail(), "0901234567", "Bác sĩ " + tag, "VET", branchA));
        Map<String, Object> data = map(created.getBody().get("data"));

        assertThat(map(ok(get("/api/staff/" + number(data.get("accountId")), token(superManager)))))
                .isEqualTo(data);
    }

    @Test
    void branchManagerWithoutBranchCreatesInOwnBranch() {
        Map<String, Object> data = map(create(token(managerA), body(newEmail(), "0901234567", "Lễ tân " + tag,
                "RECEPTIONIST", null)).getBody().get("data"));

        assertThat(number(data.get("branchId"))).isEqualTo(branchA);
    }

    @Test
    void superManagerCreatesInDraftBranchAndActivationThenSucceeds() {
        long draft = branch("DRAFT");
        hours(draft);
        String smToken = token(superManager);
        ResponseEntity<Map<String, Object>> before = post("/api/branches/" + draft + "/activate", smToken, null);
        assertRule(before, "BR-QT-04");

        ResponseEntity<Map<String, Object>> created = create(smToken, body(newEmail(), "0901234567",
                "Quản lý " + tag, "BRANCH_MANAGER", draft));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> activated = post("/api/branches/" + draft + "/activate", smToken, null);
        assertThat(activated.getStatusCode()).as("%s", activated.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createdVetAppearsInPublicVetsOnlyForActiveBranch() {
        long draft = branch("DRAFT");
        String smToken = token(superManager);
        long active = number(map(create(smToken, body(newEmail(), "0901234567", "Bác sĩ hiện " + tag, "VET",
                branchA)).getBody().get("data")).get("accountId"));
        long hidden = number(map(create(smToken, body(newEmail(), "0901234567", "Bác sĩ ẩn " + tag, "VET",
                draft)).getBody().get("data")).get("accountId"));

        ResponseEntity<Map<String, Object>> vets = http.exchange("/api/public/vets", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) vets.getBody().get("data");
        Set<Long> ids = list.stream().map(v -> number(v.get("accountId"))).collect(Collectors.toSet());
        assertThat(ids).contains(active).doesNotContain(hidden);
    }

    /** Tài khoản tạo bằng API dùng được trọn vòng đời TK có sẵn (BR-TK-17, BR-QT-02). */
    @Test
    void createdStaffFullLifecycle() {
        String email = newEmail();
        create(token(superManager), body(email, "0901234567", "Quản lý " + tag, "BRANCH_MANAGER", branchA));
        String plain = tempPassword(email);

        Map<String, Object> login = map(ok(login(email, plain)));
        assertThat(map(login.get("account"))).containsEntry("mustChangePassword", true).containsEntry("role",
                "BRANCH_MANAGER");
        String token = (String) login.get("accessToken");
        assertRule(get("/api/staff", token), "BR-TK-17");
        Map<String, Object> me = map(ok(get("/api/me", token)));
        assertThat(number(map(me.get("staffProfile")).get("branchId"))).isEqualTo(branchA);

        ResponseEntity<Map<String, Object>> changed = post("/api/me/password", token,
                Map.of("currentPassword", plain, "newPassword", "MatKhauMoi123"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/staff", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/auth/logout", token, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ================================================================ #16 tạo — phân cấp, BR, validate

    @ParameterizedTest
    @CsvSource({
            "ADMIN,          VET,            A",
            "SUPER_MANAGER,  SUPER_MANAGER,  NONE",
            "BRANCH_MANAGER, BRANCH_MANAGER, NONE",
            "BRANCH_MANAGER, VET,            B"})
    void hierarchyDenialIs403AuditedAndWritesNothing(String actorRole, String role, String branch) {
        long actor = switch (actorRole) {
            case "ADMIN" -> admin;
            case "SUPER_MANAGER" -> superManager;
            default -> managerA;
        };
        Long branchId = switch (branch) {
            case "A" -> branchA;
            case "B" -> branchB;
            default -> null;
        };
        String email = newEmail();

        ResponseEntity<Map<String, Object>> response = create(token(actor),
                body(email, "0901234567", "X " + tag, role, branchId));

        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(response.getBody()).containsEntry("message", "Không có quyền truy cập dữ liệu này");
        assertNoAccount(email);
        List<Map<String, Object>> denied = audits(actor, "STAFF_ACTION_DENIED");
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0)).containsEntry("entity_type", null).containsEntry("entity_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT after_data->>'action' || '/' || (after_data->>'requestedRole')
                FROM audit_logs WHERE actor_account_id = ? AND action = 'STAFF_ACTION_DENIED'""", String.class, actor))
                .isEqualTo("CREATE/" + role);
    }

    /** docs/adr/0029 mục 2: audit 403 ghi khi transaction nghiệp vụ đã kết thúc (không còn phiên "idle in transaction"). */
    @Test
    void hierarchyDenialAuditIsWrittenAfterRollbackWithOneConnection() {
        String actorEmail = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, managerA);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_staff_no_open_tx_" + suffix;
        String trigger = "trg_it_staff_no_open_tx_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.actor_email = '%s' AND EXISTS (
                            SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database() AND pid <> pg_backend_pid()
                              AND state LIKE 'idle in transaction%%') THEN
                        RAISE EXCEPTION 'IT: transaction nghiệp vụ vẫn mở khi ghi audit';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(function, actorEmail));
        jdbc.execute("CREATE TRIGGER %s BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, function));
        try {
            assertError(create(token(managerA), body(newEmail(), "0901234567", "X", "VET", branchB)),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON audit_logs");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        assertThat(audits(managerA, "STAFF_ACTION_DENIED")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"SM_WITH_BRANCH", "VET_WITHOUT_BRANCH", "UNKNOWN_BRANCH"})
    void branchRuleIs400WithoutWriteOrAudit(String kind) {
        String email = newEmail();
        Map<String, Object> request = switch (kind) {
            case "SM_WITH_BRANCH" -> body(email, "0901234567", "X", "SUPER_MANAGER", branchA);
            case "VET_WITHOUT_BRANCH" -> body(email, "0901234567", "X", "VET", null);
            default -> body(email, "0901234567", "X", "VET", 999_999_999L);
        };
        long actor = kind.equals("SM_WITH_BRANCH") ? admin : superManager;

        assertRule(create(token(actor), request), "BR-QT-03");
        assertNoAccount(email);
        assertThat(audits(actor, "STAFF_CREATED")).isEmpty();
        assertThat(audits(actor, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void usedEmailIs400EvenForPendingCustomerAndCaseInsensitive() {
        String email = newEmail();
        jdbc.update("""
                INSERT INTO accounts (email, password_hash, role, status, pending_expires_at)
                VALUES (?, 'hash', 'CUSTOMER', 'PENDING', now() + interval '1 day')""", email);

        ResponseEntity<Map<String, Object>> response = create(token(superManager),
                body(email.toUpperCase(), "0901234567", "X", "VET", branchA));

        assertRule(response, "BR-TK-01");
        assertThat(response.getBody().get("message")).isEqualTo("Email đã được sử dụng (BR-TK-01)");
        assertThat(outbox(email)).isEmpty();
    }

    @Test
    void concurrentSameEmailGivesOneCreatedOneConflict() throws Exception {
        String email = newEmail();
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        Answer<?> real = mockingDetails(accountRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object exists = real.answer(invocation);
            if (email.equals(invocation.getArgument(0))) {
                bothChecked.await(30, TimeUnit.SECONDS);
            }
            return exists;
        }).when(accountRepository).existsByEmail(anyString());
        String token = token(superManager);

        List<ResponseEntity<Map<String, Object>>> results = parallel(
                () -> create(token, body(email, "0901234567", "Một " + tag, "VET", branchA)),
                () -> create(token, body(email, "0901234567", "Hai " + tag, "VET", branchA)));

        assertThat(results).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertError(r, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Long.class, email))
                .isEqualTo(1);
        assertThat(outbox(email)).hasSize(1);
        assertThat(audits(superManager, "STAFF_CREATED")).hasSize(1);
    }

    @Test
    void outboxFailureRollsBackAccountProfileAndAudit() {
        String email = newEmail();
        withFailingInsert("notification_outbox", "NEW.recipient_email = '" + email + "'", () ->
                assertError(create(token(superManager), body(email, "0901234567", "X", "VET", branchA)),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertNoAccount(email);
        assertThat(audits(superManager, "STAFF_CREATED")).isEmpty();
    }

    @Test
    void auditFailureRollsBackAccountProfileAndMail() {
        String email = newEmail();
        withFailingInsert("audit_logs", "NEW.action = 'STAFF_CREATED' AND NEW.after_data->>'email' = '" + email + "'",
                () -> assertError(create(token(superManager), body(email, "0901234567", "X", "VET", branchA)),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertNoAccount(email);
        assertThat(outbox(email)).isEmpty();
    }

    /** docs/adr/0029 mục 7: BCrypt của mật khẩu tạm chạy khi request không giữ connection nào. */
    @Test
    void temporaryPasswordIsHashedWithoutHoldingConnection() {
        HikariDataSource hikari = hikari();
        List<Integer> active = new ArrayList<>();
        doAnswer(invocation -> {
            synchronized (active) {
                active.add(hikari.getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertThat(create(token(superManager), body(newEmail(), "0901234567", "X", "VET", branchA))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/staff/" + resendTarget() + "/resend-initial-password", token(superManager), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(active).hasSize(2).containsOnly(0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "NO_EMAIL      | email:",
            "BAD_EMAIL     | email:",
            "PADDED_EMAIL  | email:",
            "LONG_EMAIL    | email:",
            "NO_PHONE      | phone:",
            "SHORT_PHONE   | phone:",
            "ALPHA_PHONE   | phone:",
            "BLANK_NAME    | fullName:",
            "LONG_NAME     | fullName:",
            "NO_ROLE       | role:",
            "ADMIN_ROLE    | role:",
            "CUSTOMER_ROLE | role:",
            "FOO_ROLE      | role:",
            "ZERO_BRANCH   | branchId:"})
    void invalidBodyIs400ValidationWithoutSideEffects(String kind, String field) {
        String email = newEmail();
        Map<String, Object> request = body(email, "0901234567", "X", "VET", branchA);
        switch (kind) {
            case "NO_EMAIL" -> request.remove("email");
            case "BAD_EMAIL" -> request.put("email", "khong-la-email");
            case "PADDED_EMAIL" -> request.put("email", " " + email + " ");
            case "LONG_EMAIL" -> request.put("email", "a".repeat(244) + "@petcare.test");
            case "NO_PHONE" -> request.remove("phone");
            case "SHORT_PHONE" -> request.put("phone", "090123456");
            case "ALPHA_PHONE" -> request.put("phone", "09012345ab");
            case "BLANK_NAME" -> request.put("fullName", "   ");
            case "LONG_NAME" -> request.put("fullName", "a".repeat(101));
            case "NO_ROLE" -> request.remove("role");
            case "ADMIN_ROLE" -> request.put("role", "ADMIN");
            case "CUSTOMER_ROLE" -> request.put("role", "CUSTOMER");
            case "FOO_ROLE" -> request.put("role", "FOO");
            case "ZERO_BRANCH" -> request.put("branchId", 0);
            default -> throw new IllegalArgumentException(kind);
        }
        String token = token(superManager);
        clearInvocations(passwordEncoder);

        ResponseEntity<Map<String, Object>> response = create(token, request);

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) response.getBody().get("message")).startsWith(field);
        assertNoAccount(email);
        verify(passwordEncoder, never()).encode(any());
        assertThat(audits(superManager, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void malformedBodiesAre400MalformedOr415() {
        String token = token(superManager);

        assertError(raw(token, "{\"email\":", MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(token, "{\"email\":\"a@b.c\",\"phone\":\"0901234567\",\"fullName\":\"X\",\"role\":\"VET\","
                + "\"branchId\":\"abc\"}", MediaType.APPLICATION_JSON), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(token, "x", MediaType.TEXT_PLAIN), HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unknownFieldsAreIgnored() {
        String email = newEmail();
        Map<String, Object> request = body(email, "0901234567", "X " + tag, "VET", branchA);
        request.put("password", "TuChon12345");
        request.put("mustChangePassword", false);
        request.put("status", "DISABLED");
        request.put("isLocked", true);

        Map<String, Object> data = map(create(token(superManager), request).getBody().get("data"));

        assertThat(data).containsEntry("status", "ACTIVE").containsEntry("mustChangePassword", true)
                .containsEntry("isLocked", false);
        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("TuChon12345", hash)).isFalse();
    }

    @Test
    void wrongRoleIs403AccessDeniedWithoutAuditButBadBodyIs400First() {
        long vet = staff("VET", branchA);
        long customer = customer();

        assertError(create(token(vet), body(newEmail(), "0901234567", "X", "VET", branchA)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(create(token(customer), body(newEmail(), "0901234567", "X", "VET", branchA)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(create(token(customer), body("sai", "0901234567", "X", "VET", branchA)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(get("/api/staff", token(vet)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(post("/api/staff/" + vet + "/resend-initial-password", token(customer), null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(audits(vet, "STAFF_ACTION_DENIED")).isEmpty();
        assertThat(audits(customer, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void withoutTokenIs401AndActorMustChangePasswordIsBlocked() {
        assertError(create(null, body(newEmail(), "0901234567", "X", "VET", branchA)), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
        assertError(get("/api/staff", null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", superManager);

        assertRule(create(token(superManager), body(newEmail(), "0901234567", "X", "VET", branchA)), "BR-TK-17");
    }

    /** INSERT {@code staff_profiles(branch_id)} chỉ lấy {@code FOR KEY SHARE} trên chi nhánh: không chờ khóa của branch. */
    @Test
    void createDoesNotWaitForLockedBranchRow() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT status FROM branches WHERE id = ? FOR NO KEY UPDATE", String.class,
                        branchA);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            ResponseEntity<Map<String, Object>> created = create(token(superManager),
                    body(newEmail(), "0901234567", "X", "BRANCH_MANAGER", branchA));

            assertThat(created.getStatusCode()).as("trả về khi khóa chi nhánh vẫn đang giữ")
                    .isEqualTo(HttpStatus.CREATED);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    // ================================================================ #15 danh sách, #17 chi tiết

    @Test
    void listScopeMatrixForEachActor() {
        long smOther = staff("SUPER_MANAGER", null);
        long bmB = staff("BRANCH_MANAGER", branchB);
        long vetA = staff("VET", branchA);
        long recA = staff("RECEPTIONIST", branchA);
        long careB = staff("CARETAKER", branchB);
        long adminOther = staff("ADMIN", null);
        customer();

        assertThat(listIds(admin, "")).containsExactlyInAnyOrder(superManager, smOther);
        assertThat(listIds(superManager, "")).containsExactlyInAnyOrder(managerA, bmB, vetA, recA, careB);
        assertThat(listIds(managerA, "")).containsExactlyInAnyOrder(vetA, recA);
        assertThat(listIds(superManager, "&branchId=" + branchB)).containsExactlyInAnyOrder(bmB, careB);
        assertThat(listIds(managerA, "&branchId=" + branchA)).containsExactlyInAnyOrder(vetA, recA);
        assertThat(listIds(admin, "&branchId=" + branchA)).as("SUPER_MANAGER không có chi nhánh").isEmpty();
        assertThat(listIds(admin, "&role=VET")).as("role ngoài phạm vi → rỗng").isEmpty();
        assertThat(listIds(superManager, "&role=ADMIN")).isEmpty();
        assertThat(listIds(managerA, "&role=BRANCH_MANAGER")).isEmpty();
        assertThat(listIds(superManager, "&role=VET")).containsExactly(vetA);
        assertThat(adminOther).isPositive();

        assertError(get("/api/staff?branchId=" + branchB, token(managerA)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(audits(managerA, "STAFF_ACTION_DENIED")).as("phạm vi xem: 403 không audit").isEmpty();
    }

    @Test
    void listFiltersStatusSearchAndPaging() {
        long vet1 = staff("VET", branchA);
        long vet2 = staff("VET", branchA);
        long vet3 = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", vet3);
        String email2 = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, vet2);

        assertThat(listIds(superManager, "&status=DISABLED")).containsExactly(vet3);
        assertThat(listIds(superManager, "&status=ACTIVE")).containsExactlyInAnyOrder(managerA, vet1, vet2);
        assertThat(ids(map(ok(get("/api/staff?q=" + email2.toUpperCase(), token(superManager))))))
                .containsExactly(vet2);
        assertThat(ids(map(ok(get("/api/staff?q=%25", token(superManager)))))).as("% được escape")
                .doesNotContain(vet1, vet2, vet3);

        Map<String, Object> page = map(ok(get("/api/staff?q=" + tag + "&page=1&size=2", token(superManager))));
        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 4)
                .containsEntry("totalPages", 2);
        assertThat(ids(map(ok(get("/api/staff?q=" + tag + "&page=-5&size=1000", token(superManager))))))
                .hasSize(4);
    }

    @Test
    void eachFieldComesFromItsColumnInListAndGet() {
        long vet = staff("VET", branchB);
        jdbc.update("""
                UPDATE accounts SET email = ?, phone = '0911111111', is_locked = true, locked_reason = 'IT',
                       must_change_password = true, last_seen_at = now(), locked_until = now() + interval '1 hour'
                WHERE id = ?""", "col-email-" + tag.toLowerCase() + "@petcare.test", vet);
        jdbc.update("""
                UPDATE staff_profiles SET full_name = ?, specialty = 'col-specialty', bio = 'col-bio',
                       avatar_url = 'https://img.test/col.png'
                WHERE account_id = ?""", "col-name " + tag, vet);
        Map<String, Object> expected = new HashMap<>();
        expected.put("accountId", (int) vet);
        expected.put("email", "col-email-" + tag.toLowerCase() + "@petcare.test");
        expected.put("phone", "0911111111");
        expected.put("fullName", "col-name " + tag);
        expected.put("role", "VET");
        expected.put("status", "ACTIVE");
        expected.put("isLocked", true);
        expected.put("branchId", (int) branchB);
        expected.put("specialty", "col-specialty");
        expected.put("bio", "col-bio");
        expected.put("mustChangePassword", true);
        expected.put("online", true);

        Map<String, Object> got = map(ok(get("/api/staff/" + vet, token(superManager))));
        assertThat(got).isEqualTo(expected);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) map(ok(get("/api/staff?q=col-name+" + tag,
                token(superManager)))).get("content");
        assertThat(content).containsExactly(expected);

        jdbc.update("UPDATE accounts SET is_locked = false, locked_reason = NULL, last_seen_at = NULL WHERE id = ?",
                vet);
        assertThat(map(ok(get("/api/staff/" + vet, token(superManager)))))
                .as("khóa tạm locked_until không làm isLocked = true").containsEntry("isLocked", false)
                .containsEntry("online", false);
    }

    @Test
    void getMissingAndCustomerAre404SameMessageOutOfScopeIs403() {
        long customer = customer();
        long vetB = staff("VET", branchB);

        ResponseEntity<Map<String, Object>> missing = get("/api/staff/999999999", token(superManager));
        ResponseEntity<Map<String, Object>> ofCustomer = get("/api/staff/" + customer, token(superManager));
        assertError(missing, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(ofCustomer, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(ofCustomer.getBody().get("message")).isEqualTo("Không tìm thấy nhân viên #" + customer);

        assertError(get("/api/staff/" + vetB, token(managerA)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(get("/api/staff/" + vetB, token(admin)), HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(get("/api/staff/" + superManager, token(superManager)), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(audits(managerA, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void badQueryAndPathTypesAre400() {
        String token = token(superManager);

        assertError(get("/api/staff?role=FOO", token), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get("/api/staff?status=X", token), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get("/api/staff?branchId=abc", token), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(get("/api/staff?branchId=0", token), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(get("/api/staff/abc", token), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(post("/api/staff/abc/resend-initial-password", token, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    // ================================================================ #22 gửi lại mật khẩu ban đầu

    @Test
    void resendReplacesPasswordRevokesSessionsClearsCountersAndAudits() {
        String email = newEmail();
        long id = number(map(create(token(superManager), body(email, "0901234567", "Bác sĩ " + tag, "VET",
                branchA)).getBody().get("data")).get("accountId"));
        String oldPassword = tempPassword(email);
        String oldToken = (String) map(ok(login(email, oldPassword))).get("accessToken");
        jdbc.update("""
                UPDATE accounts SET failed_login_count = 3, first_failed_login_at = now(),
                       locked_until = now() + interval '10 minutes', last_seen_at = now() WHERE id = ?""", id);

        ResponseEntity<Map<String, Object>> response = post("/api/staff/" + id + "/resend-initial-password",
                token(managerA), null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT failed_login_count, first_failed_login_at, locked_until, last_seen_at, must_change_password
                FROM accounts WHERE id = ?""", id)).containsEntry("failed_login_count", 0)
                .containsEntry("first_failed_login_at", null).containsEntry("locked_until", null)
                .containsEntry("last_seen_at", null).containsEntry("must_change_password", true);
        assertError(get("/api/me", oldToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(outbox(email)).hasSize(2);
        String newPassword = tempPassword(email);
        assertThat(newPassword).isNotEqualTo(oldPassword);
        assertThat(login(email, oldPassword).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, newPassword).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> resent = audits(managerA, "STAFF_INITIAL_PASSWORD_RESENT");
        assertThat(resent).hasSize(1);
        assertThat(resent.get(0)).containsEntry("entity_id", id).containsEntry("after_data", null);
    }

    @Test
    void resendTwiceOnlyLatestPasswordWorks() {
        String email = newEmail();
        long id = number(map(create(token(superManager), body(email, "0901234567", "X", "VET", branchA))
                .getBody().get("data")).get("accountId"));
        String token = token(superManager);

        post("/api/staff/" + id + "/resend-initial-password", token, null);
        String second = tempPassword(email);
        post("/api/staff/" + id + "/resend-initial-password", token, null);
        String third = tempPassword(email);

        assertThat(outbox(email)).hasSize(3);
        assertThat(login(email, second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, third).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resendConflictsForDisabledOrAlreadyChangedAndChangesNothing() {
        long disabled = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET status = 'DISABLED', must_change_password = true WHERE id = ?", disabled);
        long changed = staff("VET", branchA);
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class,
                changed);

        assertError(post("/api/staff/" + disabled + "/resend-initial-password", token(superManager), null),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        ResponseEntity<Map<String, Object>> already = post("/api/staff/" + changed + "/resend-initial-password",
                token(superManager), null);
        assertError(already, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat((String) already.getBody().get("message")).contains("đã đổi mật khẩu lần đầu");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, changed))
                .isEqualTo(hashBefore);
        assertThat(audits(superManager, "STAFF_INITIAL_PASSWORD_RESENT")).isEmpty();
    }

    @Test
    void resendOutsideHierarchyIs403AuditedAndReleasesLock() {
        long vetB = staff("VET", branchB);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vetB);

        assertError(post("/api/staff/" + vetB + "/resend-initial-password", token(managerA), null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertError(post("/api/staff/" + managerA + "/resend-initial-password", token(managerA), null),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");

        List<Map<String, Object>> denied = audits(managerA, "STAFF_ACTION_DENIED");
        assertThat(denied).extracting(row -> row.get("entity_id")).containsExactlyInAnyOrder(vetB, managerA);
        tx.executeWithoutResult(status -> assertThat(jdbc.queryForObject(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, vetB)).isEqualTo(vetB));
    }

    @Test
    void resendCustomerOrMissingIs404() {
        long customer = customer();

        assertError(post("/api/staff/" + customer + "/resend-initial-password", token(superManager), null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(post("/api/staff/999999999/resend-initial-password", token(superManager), null),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void resendOutboxFailureRollsBackHashAndSessions() {
        String email = newEmail();
        long id = number(map(create(token(superManager), body(email, "0901234567", "X", "VET", branchA))
                .getBody().get("data")).get("accountId"));
        String password = tempPassword(email);
        String staffToken = (String) map(ok(login(email, password))).get("accessToken");
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id);

        withFailingInsert("notification_outbox", "NEW.recipient_email = '" + email + "'", () ->
                assertError(post("/api/staff/" + id + "/resend-initial-password", token(superManager), null),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, id)).isEqualTo(1);
        assertThat(get("/api/me", staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, password).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits(superManager, "STAFF_INITIAL_PASSWORD_RESENT")).isEmpty();
    }

    /** Nhân viên đổi mật khẩu commit trong lúc #22 chờ khóa dòng: #22 đọc lại dưới khóa → 409, không gửi mail. */
    @Test
    void resendWaitsForConcurrentPasswordChangeThenConflicts() throws Exception {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        String email = jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, vet);
        String token = token(superManager);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE accounts SET must_change_password = false WHERE id = ?", vet),
                () -> post("/api/staff/" + vet + "/resend-initial-password", token, null));

        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat(outbox(email)).isEmpty();
    }

    /** Điều chuyển commit trong lúc #22 chờ khóa: policy dùng chi nhánh mới, đọc sau khóa (docs/adr/0029 mục 4). */
    @Test
    void resendSeesTransferCommittedWhileWaitingForLock() throws Exception {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        String token = token(managerA);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, vet),
                () -> post("/api/staff/" + vet + "/resend-initial-password", token, null));

        assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED_SCOPE_MISMATCH");
        assertThat(jdbc.queryForObject("""
                SELECT (after_data->>'targetBranchId')::bigint FROM audit_logs
                WHERE actor_account_id = ? AND action = 'STAFF_ACTION_DENIED'""", Long.class, managerA))
                .isEqualTo(branchB);
    }

    /** Luồng {@code accounts → sessions} khác (đăng xuất, đổi mật khẩu) chạy chéo: không deadlock, cả hai xong. */
    @Test
    void resendAndAccountsThenSessionsFlowBothSucceed() throws Exception {
        long vet = staff("VET", branchA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", vet);
        long sessionId = tx.execute(status -> sessions.open(vet, "203.0.113.9", "StaffAdminIT-other")).sessionId();
        String token = token(superManager);

        ResponseEntity<Map<String, Object>> response = whileAccountLocked(vet,
                () -> jdbc.update("UPDATE sessions SET revoked_at = now(), updated_at = now() WHERE id = ?",
                        sessionId),
                () -> post("/api/staff/" + vet + "/resend-initial-password", token, null));

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ================================================================ helpers — dữ liệu

    private long branch(String status) {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, ?) RETURNING id
                """, Long.class, "StaffAdminIT " + UUID.randomUUID(), status);
    }

    private void hours(long branchId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1)
                    VALUES (?, DATE '2026-10-01', ?, ?, ?)
                    """, branchId, day, LocalTime.of(8, 0), LocalTime.of(17, 0));
        }
    }

    /** Nhân viên đã đổi mật khẩu lần đầu; họ tên mang {@code tag} của test. */
    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag + " " + id, branchId);
        return id;
    }

    private long customer() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
    }

    /** Nhân viên A06–A08 của chi nhánh A còn phải đổi mật khẩu lần đầu: đích hợp lệ cho #22. */
    private long resendTarget() {
        long id = staff("CARETAKER", branchA);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);
        return id;
    }

    private static String newEmail() {
        return "staff-admin-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "StaffAdminIT")).accessToken();
    }

    private Map<String, Object> body(String email, String phone, String fullName, String role, Long branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("phone", phone);
        body.put("fullName", fullName);
        body.put("role", role);
        body.put("branchId", branchId);
        return body;
    }

    /** Mật khẩu tạm trong email mới nhất (payload outbox trước khi ST20 gửi — job tắt ở profile test). */
    private String tempPassword(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'mat_khau_tam' FROM notification_outbox
                WHERE recipient_email = ? ORDER BY id DESC LIMIT 1""", String.class, email);
    }

    private List<Map<String, Object>> outbox(String email) {
        return jdbc.queryForList("SELECT * FROM notification_outbox WHERE recipient_email = ? ORDER BY id", email);
    }

    private List<Map<String, Object>> audits(long actorId, String action) {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, before_data, after_data FROM audit_logs
                WHERE actor_account_id = ? AND action = ? ORDER BY id""", actorId, action);
    }

    private void assertNoAccount(String email) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE lower(email) = lower(?)", Long.class,
                email.strip())).isZero();
        assertThat(outbox(email.strip().toLowerCase())).isEmpty();
    }

    private List<Long> listIds(long actor, String query) {
        return ids(map(ok(get("/api/staff?size=100&q=" + tag + query, token(actor)))));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream().map(row -> number(row.get("accountId")))
                .toList();
    }

    /** Trigger tạm làm INSERT vào {@code table} lỗi khi {@code condition} đúng (khuôn {@code LinkProfileIT}). */
    private void withFailingInsert(String table, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_staff_fail_" + suffix;
        String trigger = "trg_it_staff_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, table, function));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ================================================================ helpers — đồng thời

    private List<ResponseEntity<Map<String, Object>>> parallel(
            Callable<ResponseEntity<Map<String, Object>>> first, Callable<ResponseEntity<Map<String, Object>>> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map<String, Object>>> a = pool.submit(first);
            Future<ResponseEntity<Map<String, Object>>> b = pool.submit(second);
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Transaction A khóa dòng {@code accounts} ({@code FOR UPDATE}), chạy {@code whileHolding}; request chạy ở thread
     * khác và phải đứng chờ khóa (thấy trong {@code pg_stat_activity}) trước khi A commit (khuôn {@code StaffProfileIT}).
     */
    private ResponseEntity<Map<String, Object>> whileAccountLocked(long accountId, Runnable whileHolding,
            Callable<ResponseEntity<Map<String, Object>>> request) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
                whileHolding.run();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<Map<String, Object>>> call = pool.submit(request);
            awaitWaitingOnAccountLock();
            assertThat(call.isDone()).as("request phải chờ khóa dòng accounts").isFalse();
            release.countDown();

            holder.get(30, TimeUnit.SECONDS);
            return call.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaitingOnAccountLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_ACCOUNT_LOCK, Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("request phải đứng chờ khóa; pg_stat_activity = " + jdbc.queryForList("""
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

    private ResponseEntity<Map<String, Object>> create(String token, Map<String, Object> body) {
        return post("/api/staff", token, body);
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

    private ResponseEntity<Map<String, Object>> raw(String token, String body, MediaType type) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(type);
        return http.exchange("/api/staff", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
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

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường (docs/api/00-method.md §3.5). */
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
