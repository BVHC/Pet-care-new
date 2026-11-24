package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.identity.exception.GoogleSignInUnavailableException;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.module.identity.google.GoogleIdentity;
import com.petcare.module.identity.google.GoogleIdentityVerifier;
import com.petcare.support.MutableClock;

/**
 * {@code POST /api/auth/google} (identity-v1 #40, UC01 + UC03, docs/adr/0152) qua HTTP trên Postgres 17 thật, với
 * module khách thật ({@code CustomerApi} chỉ là spy gọi hàm thật; chỉ ca race làm chậm nó). Chỉ
 * {@link GoogleIdentityVerifier} là giả: IT không bao giờ gọi Google. Chứng minh những gì unit test dùng mock không chứng minh được: xóa {@code PENDING} rồi tạo lại cùng email
 * trên DB thật, audit 401 lệch {@code sub} commit nhờ {@code noRollbackFor}, cờ BR-TK-19 đọc trong cùng transaction,
 * context dựng được {@code LoginAttemptService} với {@code EntityManager} tiêm qua constructor, race tạo trùng.
 * <p>
 * Era 2093 (tương lai: không job nào quét); mỗi test dùng email / {@code sub} UUID riêng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, GoogleLoginIT.TestBeans.class})
class GoogleLoginIT {

    /** 09:00 giờ Việt Nam, 2093. */
    private static final Instant T0 = Instant.parse("2093-03-02T02:00:00Z");
    private static final String CREDENTIAL = "credential-gia-dinh-cua-it";
    private static final String PASSWORD = "matkhau123";
    private static final String GOOGLE_FAILED = "Đăng nhập Google không thành công. Vui lòng thử lại";
    private static final PasswordEncoder FAST = new BCryptPasswordEncoder(4);
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
    private GoogleIdentityVerifier verifier;

    /**
     * Spy chạy hàm thật; chỉ ca race làm chậm nó để hai transaction chồng nhau. Bean là proxy giao dịch: stub phải đặt
     * trên đối tượng đích ({@link AopTestUtils}), không phải trên proxy.
     */
    @MockitoSpyBean
    private CustomerApi customerApi;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MutableClock clock;

    private final List<String> counterPhones = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(T0);
    }

    /** Hồ sơ tại quầy chèn để thử BR-TK-19 không được còn lại cho IT khác đăng ký cùng SĐT. */
    @AfterEach
    void removeCounterProfiles() {
        counterPhones.forEach(phone -> jdbc.update(
                "DELETE FROM customers WHERE created_channel = 'COUNTER' AND phone = ?", phone));
    }

    // ---------------------------------------------------------------- tạo mới / đăng nhập lại / gắn tài khoản

    @Test
    void newGmailCustomerGets200AndRows() {
        String email = email();
        String sub = sub();
        stub(sub, email, "Nguyễn Thị Lan", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("code", 200).containsEntry("message", "Đăng nhập thành công");
        Map<String, Object> data = data(response);
        assertThat(data).containsOnlyKeys("accessToken", "expiresAt", "accessTokenExpiresAt", "refreshToken", "account",
                "linkDecisionPending").containsEntry("linkDecisionPending", false);
        Map<String, Object> account = account(data);
        assertThat(account).containsEntry("email", email).containsEntry("role", "CUSTOMER")
                .containsEntry("status", "ACTIVE").containsEntry("isLocked", false);
        long accountId = ((Number) account.get("id")).longValue();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, role, google_sub, password_hash FROM accounts WHERE id = ?", accountId);
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("role", "CUSTOMER")
                .containsEntry("google_sub", sub);
        assertThat((String) row.get("password_hash")).as("hash không dùng được nhưng không rỗng").startsWith("$2");
        assertThat(jdbc.queryForMap("SELECT full_name, created_channel FROM customers WHERE account_id = ?", accountId))
                .containsEntry("full_name", "Nguyễn Thị Lan").containsEntry("created_channel", "ONLINE");
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", accountId)).as("không OTP").isZero();
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE recipient_email = ?", email))
                .as("không gửi email nào").isZero();

        ResponseEntity<Map<String, Object>> me = get("/api/me", (String) data.get("accessToken"));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(me)).containsEntry("linkDecisionPending", false);
        assertThat(data(me).get("customerId")).isNotNull();

        List<Map<String, Object>> audits = audits(email);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "LOGIN_SUCCEEDED").containsEntry("actor_account_id", accountId);
        JsonNode after = afterData(audits.get(0));
        assertThat(after.path("method").asText()).isEqualTo("GOOGLE");
        assertThat(after.path("accountCreated").asBoolean()).isTrue();
    }

    @Test
    void secondLoginWithSameSubReturnsSameAccount() {
        String email = email();
        stub(sub(), email, "Lan", true);
        long first = id(account(data(google(true, true, null))));

        ResponseEntity<Map<String, Object>> again = google(null, null, null);

        assertThat(again.getStatusCode()).as("không cần xác nhận lại").isEqualTo(HttpStatus.OK);
        assertThat(id(account(data(again)))).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sessions WHERE account_id = ?", first)).isEqualTo(2);
        assertThat(audits(email)).extracting(a -> a.get("action")).containsExactly("LOGIN_SUCCEEDED", "LOGIN_SUCCEEDED");
    }

    @Test
    void existingPasswordAccountIsLinkedAndPasswordStillWorks() {
        String email = activeCustomer(PASSWORD);
        long id = accountId(email);
        String sub = sub();
        stub(sub, email, "Tên Google", true);

        ResponseEntity<Map<String, Object>> response = google(null, null, null);

        assertThat(response.getStatusCode()).as("không cần xác nhận: tài khoản đã có").isEqualTo(HttpStatus.OK);
        assertThat(id(account(data(response)))).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isEqualTo(sub);
        assertThat(count("SELECT count(*) FROM customers WHERE account_id = ?", id)).as("giữ hồ sơ cũ").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM customers WHERE account_id = ?", String.class, id))
                .isEqualTo("Khách IT");

        ResponseEntity<Map<String, Object>> byPassword = passwordLogin(email, PASSWORD);
        assertThat(byPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(account(data(byPassword)))).isEqualTo(id);
        assertThat(audits(email).get(0)).containsEntry("action", "LOGIN_SUCCEEDED");
    }

    @Test
    void pendingRegisteredByOtherIsReplaced() {
        String email = email();
        long oldId = registerPending(email, "Attacker123");
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", oldId)).isEqualTo(1);
        stub(sub(), email, "Chủ thật của email", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        long newId = id(account(data(response)));
        assertThat(newId).as("tài khoản mới, không kích hoạt tại chỗ").isNotEqualTo(oldId);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", oldId)).isZero();
        assertThat(count("SELECT count(*) FROM customers WHERE account_id = ?", oldId)).isZero();
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", oldId)).isZero();
        assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, newId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, newId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT full_name FROM customers WHERE account_id = ?", String.class, newId))
                .isEqualTo("Chủ thật của email");

        ResponseEntity<Map<String, Object>> attacker = passwordLogin(email, "Attacker123");
        assertError(attacker, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Email hoặc mật khẩu không đúng");
    }

    @Test
    void pendingWithoutConsentIs400AndNothingIsDeleted() {
        String email = email();
        long oldId = registerPending(email, "Attacker123");
        stub(sub(), email, "Chủ thật", true);

        ResponseEntity<Map<String, Object>> response = google(null, null, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-02)");
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND status = 'PENDING'", oldId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customers WHERE account_id = ?", oldId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", oldId)).isEqualTo(1);
    }

    @Test
    void subMismatchIs401AndAuditRowIsCommitted() {
        String email = activeCustomer(PASSWORD);
        long id = accountId(email);
        jdbc.update("UPDATE accounts SET google_sub = 'sub-goc' WHERE id = ?", id);
        stub(sub(), email, "Người khác", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", GOOGLE_FAILED);
        assertThat(response.getBody()).containsEntry("message", GOOGLE_FAILED);
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id))
                .as("không gắn đè").isEqualTo("sub-goc");
        assertThat(count("SELECT count(*) FROM sessions WHERE account_id = ?", id)).isZero();
        // Đọc bằng connection khác sau khi request kết thúc: audit trong transaction phải commit dù ném 401.
        List<Map<String, Object>> audits = audits(email);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("action", "LOGIN_FAILED").containsEntry("reason", "GOOGLE_SUB_MISMATCH")
                .containsEntry("actor_account_id", id);
    }

    @Test
    void nonAuthoritativeExistingAccountIs400BrTk01() {
        String email = activeCustomer(PASSWORD);
        long id = accountId(email);
        stub(sub(), email, "Ai đó", false);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-01)");
        assertThat((String) response.getBody().get("message"))
                .startsWith("Email này đã có tài khoản. Vui lòng đăng nhập bằng email và mật khẩu");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isNull();
        assertThat(count("SELECT count(*) FROM sessions WHERE account_id = ?", id)).isZero();
        assertThat(audits(email)).extracting(a -> a.get("reason")).containsExactly("GOOGLE_EMAIL_NOT_AUTHORITATIVE");
    }

    @Test
    void nonAuthoritativeEmailWithPendingAccountKeepsIt() {
        String email = email();
        long oldId = registerPending(email, "Attacker123");
        stub(sub(), email, "Ai đó", false);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-01)");
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND status = 'PENDING'", oldId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM otp_tokens WHERE account_id = ?", oldId)).isEqualTo(1);
    }

    @Test
    void nonAuthoritativeEmailWithoutAnyAccountStillCreatesOne() {
        String email = "google-it-" + UUID.randomUUID() + "@yahoo.com";
        stub(sub(), email, "Người dùng Yahoo", false);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account(data(response))).containsEntry("email", email).containsEntry("status", "ACTIVE");
    }

    @Test
    void staffEmailIs400BrTk17() {
        String email = "google-it-staff-" + UUID.randomUUID() + "@petcare.test";
        long branchId = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "GoogleLoginIT chi nhánh " + UUID.randomUUID());
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', ?, 'RECEPTIONIST', 'ACTIVE') RETURNING id
                """, Long.class, email, FAST.encode(PASSWORD));
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Nhân viên IT', ?)",
                id, branchId);
        stub(sub(), email, "Nhân viên", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-17)");
        assertThat((String) response.getBody().get("message"))
                .startsWith("Tài khoản nhân viên đăng nhập bằng email và mật khẩu");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isNull();
        assertThat(audits(email)).extracting(a -> a.get("reason")).containsExactly("GOOGLE_STAFF");
    }

    @Test
    void lockedCustomerIs400BrTk11AndNothingIsLinked() {
        String email = activeCustomer(PASSWORD);
        long id = accountId(email);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", id);
        stub(sub(), email, "Khách bị khóa", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-11)");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isNull();
        assertThat(count("SELECT count(*) FROM sessions WHERE account_id = ?", id)).isZero();
    }

    @Test
    void temporarilyLockedCustomerIs400BrTk09AndNothingIsLinked() {
        String email = activeCustomer(PASSWORD);
        long id = accountId(email);
        jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(T0.plusSeconds(600)), id);
        stub(sub(), email, "Khách khóa tạm", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-09)");
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isNull();
        assertThat(count("SELECT count(*) FROM sessions WHERE account_id = ?", id)).isZero();
        assertThat(audits(email)).extracting(a -> a.get("reason")).containsExactly("TEMPORARILY_LOCKED");
    }

    @Test
    void missingConsentIs400BrTk02ThenConsentGives200() {
        String email = email();
        stub(sub(), email, "Khách mới", true);

        assertError(google(null, null, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-02)");
        assertError(google(true, null, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-02)");
        assertError(google(true, false, null), HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", "(BR-TK-02)");
        assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).as("chưa ghi gì").isZero();

        ResponseEntity<Map<String, Object>> consented = google(true, true, null);

        assertThat(consented.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void sameSubWithChangedGoogleEmailLogsIntoTheSameAccount() {
        String sub = sub();
        String email = email();
        stub(sub, email, "Lan", true);
        long id = id(account(data(google(true, true, null))));
        stub(sub, email(), "Lan", true);

        ResponseEntity<Map<String, Object>> response = google(null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account(data(response))).containsEntry("email", email);
        assertThat(id(account(data(response)))).isEqualTo(id);
    }

    // ---------------------------------------------------------------- BR-TK-19, mật khẩu, lỗi hạ tầng

    @Test
    void phoneMatchingCounterProfileSetsLinkDecision() {
        String phone = counterProfile();
        stub(sub(), email(), "Khách mới", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = data(response);
        assertThat(data).containsEntry("linkDecisionPending", true);
        assertThat(jdbc.queryForObject("SELECT link_decision_pending FROM customers WHERE account_id = ?",
                Boolean.class, id(account(data)))).isTrue();
        ResponseEntity<Map<String, Object>> me = get("/api/me", (String) data.get("accessToken"));
        assertThat(data(me)).containsEntry("linkDecisionPending", true);
    }

    @Test
    void unmatchedPhoneDoesNotFlagLinkDecision() {
        stub(sub(), email(), "Khách mới", true);

        ResponseEntity<Map<String, Object>> response = google(true, true, randomPhone());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response)).containsEntry("linkDecisionPending", false);
    }

    @Test
    void googleOnlyAccountCannotPasswordLogin() {
        String email = email();
        stub(sub(), email, "Khách Google", true);
        assertThat(google(true, true, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (String guess : List.of(PASSWORD, "Attacker123", CREDENTIAL)) {
            assertError(passwordLogin(email, guess), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Email hoặc mật khẩu không đúng");
        }
    }

    /**
     * UC04 (docs/adr/0152): đặt lại mật khẩu bằng OTP thật gỡ {@code google_sub}; Google lại với cùng {@code sub} và
     * email Gmail gắn lại đúng tài khoản đó (không tạo tài khoản mới), mật khẩu vừa đặt vẫn đăng nhập được.
     */
    @Test
    void passwordResetUnlinksGoogleSub() {
        String email = email();
        String sub = sub();
        stub(sub, email, "Khách Google", true);
        long id = id(account(data(google(true, true, null))));
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isEqualTo(sub);

        assertThat(postJson("/api/auth/password/forgot", Map.of("email", email)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String code = jdbc.queryForObject("""
                SELECT payload ->> 'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_PASSWORD_RESET' ORDER BY id DESC LIMIT 1
                """, String.class, email);
        ResponseEntity<Map<String, Object>> reset = postJson("/api/auth/password/reset",
                Map.of("email", email, "code", code, "newPassword", "matkhaumoi9"));

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT google_sub IS NULL FROM accounts WHERE id = ?", Boolean.class, id))
                .as("đặt lại mật khẩu gỡ liên kết Google").isTrue();
        assertThat(passwordLogin(email, "matkhaumoi9").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> again = google(null, null, null);

        assertThat(again.getStatusCode()).as("Gmail tự gắn lại, không cần xác nhận").isEqualTo(HttpStatus.OK);
        assertThat(id(account(data(again)))).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT google_sub FROM accounts WHERE id = ?", String.class, id)).isEqualTo(sub);
        assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).isEqualTo(1);
    }

    /**
     * Kiểm ID token gọi mạng tới Google nên phải chạy khi chưa mở transaction (docs/adr/0152, {@code GoogleLoginService}
     * không {@code @Transactional}): gắn {@code @Transactional} lên service đó làm ca này đỏ. Unit
     * {@code verifiesBeforeTransactionalPart} chỉ khóa thứ tự gọi nên không bắt được lỗi đó.
     */
    @Test
    void verifierRunsOutsideTransaction() {
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        String email = email();
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new GoogleIdentity(sub(), email, "Khách Google", true);
        }).when(verifier).verify(any());

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionActive.get()).as("verifier đã được gọi").isNotNull();
        assertThat(transactionActive.get()).as("verify chạy ngoài transaction").isFalse();
    }

    @Test
    void verifierUnavailableIs503() {
        when(verifier.verify(any())).thenThrow(new GoogleSignInUnavailableException());

        ResponseEntity<Map<String, Object>> response = google(true, true, null);

        assertError(response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                GoogleSignInUnavailableException.MESSAGE);
    }

    @Test
    void invalidTokenIs401() {
        when(verifier.verify(any()))
                .thenThrow(new InvalidCredentialsException(InvalidCredentialsException.GOOGLE_MESSAGE));

        assertError(google(true, true, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", GOOGLE_FAILED);
    }

    @Test
    void blankCredentialIs400ValidationFailed() {
        ResponseEntity<Map<String, Object>> response = googleRaw("{\"credential\": \"   \"}");

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains("Thiếu mã đăng nhập Google");
        verifyNoInteractions(verifier);
    }

    @Test
    void emptyPhoneIs400ValidationFailed() {
        ResponseEntity<Map<String, Object>> response = googleRaw(
                "{\"credential\": \"" + CREDENTIAL + "\", \"phone\": \"\"}");

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertThat((String) response.getBody().get("message")).contains("Số điện thoại phải có dạng 0xxxxxxxxx");
        verifyNoInteractions(verifier);
    }

    @Test
    void malformedBodyIs400MalformedRequest() {
        assertError(googleRaw("{\"credential\": "), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", null);
        assertError(googleRaw("{\"credential\": \"x\", \"isAdult\": \"khong\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", null);
        verifyNoInteractions(verifier);
    }

    // ---------------------------------------------------------------- race

    /**
     * Hai request cùng token tạo tài khoản lần đầu cùng lúc: chậm lệnh tạo hồ sơ để transaction chồng nhau. Một request
     * thắng (200); request kia 200 (đến sau commit) hoặc 409 (chạm unique), không bao giờ 500, và chỉ có một dòng.
     */
    @Test
    void concurrentFirstLoginsNeverReturn500() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(300);
            return invocation.callRealMethod();
        }).when(AopTestUtils.<CustomerApi>getUltimateTargetObject(customerApi)).createOnlineProfile(any(), any(), any());
        String email = email();
        String sub = sub();
        stub(sub, email, "Khách song song", true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return google(true, true, null);
                }));
            }
            start.countDown();
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSizeGreaterThanOrEqualTo(1);
            for (ResponseEntity<Map<String, Object>> r : responses) {
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertError(r, HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", null);
                    assertThat((String) r.getBody().get("message")).doesNotContain("uq_");
                } else {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                }
            }
            assertThat(count("SELECT count(*) FROM accounts WHERE email = ?", email)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM accounts WHERE google_sub = ?", sub)).isEqualTo(1);
            assertThat(google(null, null, null).getStatusCode()).as("bấm lại là đăng nhập được")
                    .isEqualTo(HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void stub(String sub, String email, String name, boolean authoritative) {
        when(verifier.verify(any())).thenReturn(new GoogleIdentity(sub, email, name, authoritative));
    }

    private ResponseEntity<Map<String, Object>> google(Boolean isAdult, Boolean termsAccepted, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credential", CREDENTIAL);
        if (isAdult != null) {
            body.put("isAdult", isAdult);
        }
        if (termsAccepted != null) {
            body.put("termsAccepted", termsAccepted);
        }
        if (phone != null) {
            body.put("phone", phone);
        }
        return googleRaw(write(body));
    }

    private ResponseEntity<Map<String, Object>> googleRaw(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/auth/google", HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> passwordLogin(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(write(Map.of("email", email, "password", password)), headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(write(body), headers), MAP);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> account(Map<String, Object> loginData) {
        return (Map<String, Object>) loginData.get("account");
    }

    private static long id(Map<String, Object> account) {
        return ((Number) account.get("id")).longValue();
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode, String messageSuffix) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        assertThat((String) body.get("message")).isNotBlank();
        if (messageSuffix != null) {
            assertThat((String) body.get("message")).endsWith(messageSuffix);
        }
    }

    // ---------------------------------------------------------------- dữ liệu

    private static String email() {
        return "google-it-" + UUID.randomUUID() + "@gmail.com";
    }

    private static String sub() {
        return "sub-" + UUID.randomUUID();
    }

    /** Khách ACTIVE có mật khẩu và hồ sơ online, chưa gắn Google. */
    private String activeCustomer(String password) {
        String email = email();
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, ?, 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email, FAST.encode(password));
        jdbc.update("INSERT INTO customers (account_id, full_name, created_channel) VALUES (?, 'Khách IT', 'ONLINE')", id);
        return email;
    }

    /** Đăng ký thật qua HTTP (người đăng ký trước bằng email của nạn nhân): tài khoản PENDING + hồ sơ online + OTP. */
    private long registerPending(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("fullName", "Kẻ đăng ký trước");
        body.put("isAdult", true);
        body.put("termsAccepted", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = http.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(write(body), headers), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return accountId(email);
    }

    private static String randomPhone() {
        return String.format("09%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    /** Hồ sơ tại quầy chưa liên kết với SĐT riêng của test (BR-TK-19); dọn ở {@link #removeCounterProfiles()}. */
    private String counterProfile() {
        String phone = randomPhone();
        jdbc.update("INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khách quầy IT', ?, 'COUNTER')",
                phone);
        counterPhones.add(phone);
        return phone;
    }

    private long accountId(String email) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE email = ?", Long.class, email);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<Map<String, Object>> audits(String email) {
        return jdbc.queryForList("""
                SELECT action, reason, actor_account_id, entity_id, after_data::text AS after_data
                FROM audit_logs WHERE actor_email = ? ORDER BY id
                """, email);
    }

    private JsonNode afterData(Map<String, Object> audit) {
        try {
            return json.readTree((String) audit.get("after_data"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
