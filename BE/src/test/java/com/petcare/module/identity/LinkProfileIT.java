package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.identity.service.SessionService;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * UC07 — liên kết tài khoản khách với hồ sơ tại quầy (identity-v1 #11–14; BR-TK-04…07, 11, 19; docs/adr/0025, 0027)
 * qua HTTP trên Postgres 17. Module customer (BE-2) chưa cài — nợ D001, D010 — nên {@link JdbcCustomerModule} đứng thay:
 * làm đúng nghĩa vụ ghi ở javadoc {@code CustomerApi} bằng SQL thật trong transaction của identity (khóa {@code customers},
 * kiểm lại, xóa {@code addresses} của hồ sơ online rồi hồ sơ online, rồi mới gắn hồ sơ tại quầy). Mỗi test có SĐT và
 * email hồ sơ riêng để danh sách ứng viên và quota BR-TK-07 không lẫn giữa các test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, LinkProfileIT.TestBeans.class})
class LinkProfileIT {

    private static final Instant T0 = Instant.parse("2026-10-10T03:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
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
        JdbcCustomerModule jdbcCustomerModule(JdbcTemplate jdbc) {
            return new JdbcCustomerModule(jdbc);
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
    private JdbcCustomerModule customerModule;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private TransactionTemplate tx;

    /** Khách X: tài khoản + hồ sơ online O (cờ chờ liên kết) với SĐT {@link #phone}. */
    private String accountEmail;
    private long account;
    private long online;
    private String token;

    /** Hồ sơ tại quầy C cùng SĐT, có email {@link #counterEmail}. */
    private String phone;
    private String counterEmail;
    private long counter;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        customerModule.reset();
        tx = new TransactionTemplate(transactionManager);
        phone = randomPhone();
        counterEmail = "ho-so-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
        accountEmail = newEmail();
        account = customerAccount(accountEmail);
        online = onlineProfile(account, phone, true);
        counter = counterProfile("Nguyễn Văn An", phone, counterEmail);
        token = open(account);
    }

    // ---------------------------------------------------------------- #11 danh sách ứng viên

    @Test
    void candidatesBodyMatchesContractAndDefaultsToOnlineProfilePhone() {
        long withoutEmail = counterProfile("Trần Thị Bình", phone, null);

        ResponseEntity<Map<String, Object>> response = get("/api/me/link-candidates", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        List<Map<String, Object>> data = list(response.getBody().get("data"));
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).containsOnlyKeys("customerId", "maskedFullName", "hasEmail")
                .containsEntry("maskedFullName", "Ng*** V** A*").containsEntry("hasEmail", true);
        assertThat(number(data.get(0).get("customerId"))).isEqualTo(counter);
        assertThat(data.get(1)).containsEntry("maskedFullName", "Tr*** T** B*").containsEntry("hasEmail", false);
        assertThat(number(data.get(1).get("customerId"))).isEqualTo(withoutEmail);
    }

    @Test
    void noCandidatesReturnsEmptyArray() {
        ResponseEntity<Map<String, Object>> response = get("/api/me/link-candidates?phone=" + randomPhone(), token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list(response.getBody().get("data"))).isEmpty();
    }

    @Test
    void invalidPhoneQueryIs400() {
        assertError(get("/api/me/link-candidates?phone=abc", token), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void onlineProfileWithDataIs400AtCandidatesAndOtp() {
        pet(online);

        assertRule(get("/api/me/link-candidates", token), "BR-TK-19");
        assertRule(sendOtp(token, counter, null), "BR-TK-19");
        assertThat(outboxCount()).isZero();
    }

    // ---------------------------------------------------------------- #12 gửi mã

    @Test
    void otpBodyMatchesContractAndCodeGoesToCounterProfileEmail() {
        ResponseEntity<Map<String, Object>> response = sendOtp(token, counter, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Đã gửi mã xác thực tới email của hồ sơ");
        Map<String, Object> data = map(response.getBody().get("data"));
        assertThat(data).containsOnlyKeys("resendAvailableAt", "maskedEmail")
                .containsEntry("resendAvailableAt", "2026-10-10T03:01:00Z")
                .containsEntry("maskedEmail", "ho***@petcare.test");

        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT template_code, channel, recipient_email, recipient_account_id,
                       payload->>'email_tai_khoan' AS email_tai_khoan, payload->>'ma_otp' AS ma_otp
                FROM notification_outbox WHERE recipient_email = ?""", counterEmail);
        assertThat(outbox).containsEntry("template_code", "OTP_PROFILE_LINK").containsEntry("channel", "EMAIL")
                .containsEntry("email_tai_khoan", accountEmail).containsEntry("recipient_account_id", null);
        assertThat((String) outbox.get("ma_otp")).matches("\\d{6}");
        assertThat(jdbc.queryForMap("""
                SELECT account_id, customer_id, target_email FROM otp_tokens
                WHERE purpose = 'LINK_PROFILE' AND customer_id = ?""", counter))
                .containsEntry("account_id", account).containsEntry("target_email", counterEmail);
    }

    @Test
    void otpForNonCandidateIs404BeforeDataGuardAndWritesNothing() {
        long otherPhoneProfile = counterProfile("Lê Văn C", randomPhone(), "khac-" + counterEmail);
        pet(online);

        assertError(sendOtp(token, otherPhoneProfile, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(sendOtp(token, 987_654_321L, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(outboxCount()).isZero();
        assertThat(otpCount()).isZero();
    }

    @Test
    void counterProfileWithoutEmailIs400SendsNothingAndKeepsTheFlag() {
        long withoutEmail = counterProfile("Trần Thị Bình", phone, null);

        assertRule(sendOtp(token, withoutEmail, null), "BR-TK-19");
        assertThat(otpCount()).isZero();
        assertThat(flag(online)).isTrue();
    }

    @Test
    void resendWithin60SecondsIs400AndOldCodeStaysValid() {
        sendOtp(token, counter, null);
        String first = code();
        clock.advance(Duration.ofSeconds(30));

        assertRule(sendOtp(token, counter, null), "BR-TK-07");
        assertThat(confirm(token, counter, first).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void enqueueFailureRollsBackTheNewCodeAndKeepsTheOldOne() {
        sendOtp(token, counter, null);
        long oldCode = latestCodeId();
        clock.advance(Duration.ofSeconds(61));
        withTrigger("notification_outbox", "BEFORE INSERT",
                "NEW.recipient_email = '" + counterEmail + "'",
                () -> assertError(sendOtp(token, counter, null), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(otpCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT invalidated_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                oldCode)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{}                                   | VALIDATION_FAILED",
            "{\"customerId\": 0}                  | VALIDATION_FAILED",
            "{\"customerId\": 5, \"phone\": \"123\"} | VALIDATION_FAILED",
            "{\"customerId\": \"abc\"}            | MALFORMED_REQUEST",
            "{\"customerId\":                     | MALFORMED_REQUEST"
    })
    void invalidOtpBodiesAre400AndWriteNothing(String body, String errorCode) {
        assertError(post("/api/me/link/otp", token, body), HttpStatus.BAD_REQUEST, errorCode);
        assertThat(otpCount()).isZero();
    }

    // ---------------------------------------------------------------- #13 xác nhận

    @Test
    void confirmLinksAllTogetherAndMeAgrees() {
        jdbc.update("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, province, is_default)
                VALUES (?, 'Nhận', '0900000000', '1 Đường A', 'Hà Nội', true),
                       (?, 'Nhận', '0900000000', '2 Đường B', 'Hà Nội', false)""", online, online);
        sendOtp(token, counter, null);

        ResponseEntity<Map<String, Object>> response = confirm(token, counter, code());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Liên kết hồ sơ thành công");
        Map<String, Object> data = map(response.getBody().get("data"));
        assertThat(data).containsOnlyKeys("customerId");
        assertThat(number(data.get("customerId"))).isEqualTo(counter);

        assertThat(exists(online)).as("hồ sơ online bị xóa").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, online))
                .as("D12: sổ địa chỉ online xóa cùng hồ sơ").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, counter))
                .isZero();
        assertThat(jdbc.queryForMap("SELECT account_id, email, phone FROM customers WHERE id = ?", counter))
                .containsEntry("account_id", account).containsEntry("email", accountEmail).containsEntry("phone", phone);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                latestCodeId())).isTrue();
        assertThat(audits(counter)).hasSize(1);
        Map<String, Object> audit = audits(counter).get(0);
        assertThat(audit).containsEntry("actor_account_id", account).containsEntry("actor_email", accountEmail);
        assertThat(audit.get("before_data").toString()).contains("\"onlineCustomerId\": " + online);
        assertThat(audit.get("after_data").toString()).contains("\"linkedAccountId\": " + account)
                .contains("EMAIL_CODE");

        Map<String, Object> me = map(get("/api/me", token).getBody().get("data"));
        assertThat(number(me.get("customerId"))).isEqualTo(counter);
        assertThat(me).containsEntry("linkDecisionPending", false);
        assertThat(customerModule.lastLinkArgs).containsExactly(account, counter, counterEmail, accountEmail);
    }

    @Test
    void unflaggedCustomerLinksByTypedPhone() {
        jdbc.update("UPDATE customers SET link_decision_pending = false, phone = NULL WHERE id = ?", online);
        String counterPhone = randomPhone();
        long other = counterProfile("Phạm Văn D", counterPhone, "d-" + counterEmail);

        assertThat(list(get("/api/me/link-candidates?phone=" + counterPhone, token).getBody().get("data")))
                .hasSize(1);
        assertError(sendOtp(token, other, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(sendOtp(token, other, counterPhone).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(token, other, codeFor("d-" + counterEmail)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT account_id FROM customers WHERE id = ?", Long.class, other))
                .isEqualTo(account);
    }

    @Test
    void wrongCodeIsCountedReturns400AndDoesNotLink() {
        sendOtp(token, counter, null);

        assertRule(confirm(token, counter, wrong(code())), "BR-TK-05");

        assertThat(failedAttempts()).isEqualTo(1);
        assertThat(exists(online)).isTrue();
        assertThat(linkedAccount(counter)).isNull();
        assertThat(audits(counter)).isEmpty();
    }

    @Test
    void fifthWrongCodeIs06ThenRightCodeIsRejected() {
        sendOtp(token, counter, null);
        String right = code();
        for (int i = 1; i <= 4; i++) {
            assertRule(confirm(token, counter, wrong(right)), "BR-TK-05");
        }

        assertRule(confirm(token, counter, wrong(right)), "BR-TK-06");
        assertRule(confirm(token, counter, right), "BR-TK-05");
        assertThat(failedAttempts()).isEqualTo(5);
        assertThat(linkedAccount(counter)).isNull();
    }

    @Test
    void expiredCodeIs400NotCounted() {
        sendOtp(token, counter, null);
        String right = code();
        clock.advance(Duration.ofMinutes(5));

        assertRule(confirm(token, counter, right), "BR-TK-05");
        assertThat(failedAttempts()).isZero();
    }

    @Test
    void codeForOtherProfileIs400() {
        long sameEmailProfile = counterProfile("Nguyễn Văn An", phone, counterEmail);
        sendOtp(token, counter, null);

        assertRule(confirm(token, sameEmailProfile, code()), "BR-TK-05");
        assertThat(linkedAccount(sameEmailProfile)).isNull();
        assertThat(failedAttempts()).isZero();
    }

    @Test
    void confirmUnknownProfileIs404() {
        assertError(confirm(token, 987_654_321L, "123456"), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @Test
    void dataCreatedAfterOtpIsRejectedAtConfirmAndCodeStaysUsable() {
        sendOtp(token, counter, null);
        String right = code();
        long pet = pet(online);

        assertRule(confirm(token, counter, right), "BR-TK-19");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                latestCodeId())).as("rollback cả consumed_at").isTrue();
        assertThat(failedAttempts()).isZero();

        jdbc.update("DELETE FROM pets WHERE id = ?", pet);
        assertThat(confirm(token, counter, right).getStatusCode()).as("cùng mã dùng lại được").isEqualTo(HttpStatus.OK);
    }

    @Test
    void customerRejectionAfterItsOwnWritesRollsThemBack() {
        sendOtp(token, counter, null);
        customerModule.failAfterDelete = true;

        assertRule(confirm(token, counter, code()), "BR-TK-19");

        assertThat(exists(online)).as("DELETE của customer bị rollback").isTrue();
        assertThat(linkedAccount(counter)).isNull();
        assertThat(audits(counter)).isEmpty();
    }

    @Test
    void auditFailureRollsBackTheWholeLink() {
        sendOtp(token, counter, null);
        String right = code();

        withTrigger("audit_logs", "BEFORE INSERT",
                "NEW.action = 'CUSTOMER_PROFILE_LINKED' AND NEW.entity_id = " + counter,
                () -> assertError(confirm(token, counter, right), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(exists(online)).isTrue();
        assertThat(linkedAccount(counter)).isNull();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                latestCodeId())).isTrue();
    }

    @Test
    void alreadyLinkedAccountCannotLinkAgain() {
        sendOtp(token, counter, null);
        assertThat(confirm(token, counter, code()).getStatusCode()).isEqualTo(HttpStatus.OK);
        long second = counterProfile("Nguyễn Văn An", phone, "hai-" + counterEmail);

        assertRule(get("/api/me/link-candidates", token), "BR-TK-19");
        assertRule(sendOtp(token, second, null), "BR-TK-19");
        assertThat(exists(counter)).as("không bao giờ xóa hồ sơ tại quầy").isTrue();
    }

    @Test
    void codeOfAnotherAccountIsUselessOnceTheProfileIsLinked() {
        String otherEmail = newEmail();
        long other = customerAccount(otherEmail);
        onlineProfile(other, phone, true);
        String otherToken = open(other);
        sendOtp(token, counter, null);
        String codeX = code();
        clock.advance(Duration.ofSeconds(61));
        sendOtp(otherToken, counter, null);
        String codeY = code();
        long codeIdY = latestCodeId();

        assertThat(confirm(token, counter, codeX).getStatusCode()).isEqualTo(HttpStatus.OK);

        // BR-TK-19: email hồ sơ đã theo email tài khoản X → mã của Y (gửi tới email cũ) không còn khớp (ADR-0025 mục 2)
        assertRule(confirm(otherToken, counter, codeY), "BR-TK-05");
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM otp_tokens WHERE id = ?", Integer.class, codeIdY))
                .isZero();
        assertThat(linkedAccount(counter)).isEqualTo(account);
    }

    /**
     * Y đọc mã khi hồ sơ còn trống, X liên kết xong trước khi Y lấy được khóa: customer từ chối dưới khóa (BR-TK-19),
     * mã của Y được rollback về chưa dùng.
     */
    @Test
    void profileLinkedBetweenReadAndLockIsRejectedUnderLock() throws Exception {
        String otherEmail = newEmail();
        long other = customerAccount(otherEmail);
        onlineProfile(other, phone, true);
        String otherToken = open(other);
        sendOtp(token, counter, null);
        String codeX = code();
        clock.advance(Duration.ofSeconds(61));
        sendOtp(otherToken, counter, null);
        String codeY = code();
        long codeIdY = latestCodeId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, other);
            locked.countDown();
            await(release);
        }));
        await(locked);
        CompletableFuture<ResponseEntity<Map<String, Object>>> requestY =
                CompletableFuture.supplyAsync(() -> confirm(otherToken, counter, codeY));
        waitUntilBlockedOnAccountLock(1);
        assertThat(confirm(token, counter, codeX).getStatusCode()).isEqualTo(HttpStatus.OK);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertRule(requestY.get(10, TimeUnit.SECONDS), "BR-TK-19");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM otp_tokens WHERE id = ?", Boolean.class,
                codeIdY)).isTrue();
        assertThat(linkedAccount(counter)).isEqualTo(account);
        assertThat(audits(counter)).hasSize(1);
    }

    @Test
    void concurrentWrongConfirmsAreBothCounted() throws Exception {
        sendOtp(token, counter, null);
        String bad = wrong(code());

        List<ResponseEntity<Map<String, Object>>> responses = whileAccountLocked(account, 2,
                () -> confirm(token, counter, bad));

        responses.forEach(response -> assertRule(response, "BR-TK-05"));
        assertThat(failedAttempts()).isEqualTo(2);
    }

    @Test
    void doubleSubmitConfirmLinksOnce() throws Exception {
        sendOtp(token, counter, null);
        String right = code();

        List<ResponseEntity<Map<String, Object>>> responses = whileAccountLocked(account, 2,
                () -> confirm(token, counter, right));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        assertThat(audits(counter)).hasSize(1);
    }

    @Test
    void accountLockedWhileWaitingIs400AndCodeIsUntouched() throws Exception {
        sendOtp(token, counter, null);
        String right = code();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> admin = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, account);
            jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", account);
            locked.countDown();
            await(release);
        }));
        await(locked);
        CompletableFuture<ResponseEntity<Map<String, Object>>> request =
                CompletableFuture.supplyAsync(() -> confirm(token, counter, right));
        waitUntilBlockedOnAccountLock(1);
        release.countDown();
        admin.get(10, TimeUnit.SECONDS);

        assertRule(request.get(10, TimeUnit.SECONDS), "BR-TK-11");
        assertThat(failedAttempts()).isZero();
        assertThat(linkedAccount(counter)).isNull();
    }

    @Test
    void bcryptRunsWithoutHoldingConnection() {
        HikariDataSource hikari = hikari();
        List<Integer> activeDuringBcrypt = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            activeDuringBcrypt.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
        doAnswer(invocation -> {
            activeDuringBcrypt.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());

        sendOtp(token, counter, null);
        assertThat(confirm(token, counter, code()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(activeDuringBcrypt).hasSize(2).containsOnly(0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"customerId\": 5}                       | VALIDATION_FAILED",
            "{\"customerId\": 5, \"code\": \"\"}       | VALIDATION_FAILED",
            "{\"customerId\": 5, \"code\": \"12ab56\"} | VALIDATION_FAILED",
            "{\"customerId\": 5, \"code\": \"123456789\"} | VALIDATION_FAILED",
            "{\"code\": \"123456\"}                    | VALIDATION_FAILED"
    })
    void invalidConfirmBodiesAre400(String body, String errorCode) {
        assertError(post("/api/me/link/confirm", token, body), HttpStatus.BAD_REQUEST, errorCode);
    }

    @Test
    void responsesNeverLeakCounterProfileContact() {
        List<String> bodies = new ArrayList<>();
        bodies.add(get("/api/me/link-candidates", token).getBody().toString());
        bodies.add(sendOtp(token, counter, null).getBody().toString());
        bodies.add(confirm(token, counter, wrong(code())).getBody().toString());
        bodies.add(confirm(token, counter, code()).getBody().toString());

        assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain(counterEmail)
                .doesNotContain("Nguyễn Văn An").doesNotContain(phone));
    }

    // ---------------------------------------------------------------- #14 "Không phải tôi"

    @Test
    void declineReturns204AndClearsTheFlag() {
        ResponseEntity<String> response = http.exchange("/api/me/link/decline", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(flag(online)).isFalse();
        assertThat(map(get("/api/me", token).getBody().get("data"))).containsEntry("linkDecisionPending", false);
    }

    @Test
    void declineWithoutFlagIs409() {
        jdbc.update("UPDATE customers SET link_decision_pending = false WHERE id = ?", online);

        assertError(post("/api/me/link/decline", token, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void declineAfterConfirmIs409() {
        sendOtp(token, counter, null);
        assertThat(confirm(token, counter, code()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertError(post("/api/me/link/decline", token, null), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    /**
     * Confirm và decline của cùng tài khoản chạy song song: cả hai chờ khóa {@code accounts}, confirm đứng trước nên
     * decline thấy tài khoản đã gắn hồ sơ tại quầy → 409, không trả 204 cho một lệnh không làm gì.
     */
    @Test
    void declineRacingConfirmIsSerializedByTheAccountLock() throws Exception {
        sendOtp(token, counter, null);
        String right = code();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, account);
            locked.countDown();
            await(release);
        }));
        await(locked);
        CompletableFuture<ResponseEntity<Map<String, Object>>> confirm =
                CompletableFuture.supplyAsync(() -> confirm(token, counter, right));
        waitUntilBlockedOnAccountLock(1);
        CompletableFuture<ResponseEntity<Map<String, Object>>> decline =
                CompletableFuture.supplyAsync(() -> post("/api/me/link/decline", token, null));
        waitUntilBlockedOnAccountLock(2);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(confirm.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(decline.get(10, TimeUnit.SECONDS), HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    // ---------------------------------------------------------------- quyền

    @Test
    void staffGets403OnAllFourEndpoints() {
        long staff = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000001', 'hash', 'RECEPTIONIST', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
        long branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'ACTIVE') RETURNING id""", Long.class,
                "LinkProfileIT " + UUID.randomUUID());
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Lễ tân', ?)", staff,
                branch);
        String staffToken = open(staff);

        assertError(get("/api/me/link-candidates", staffToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(sendOtp(staffToken, counter, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(confirm(staffToken, counter, "123456"), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(post("/api/me/link/decline", staffToken, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertThat(otpCount()).isZero();
    }

    @Test
    void anonymousGets401() {
        assertError(get("/api/me/link-candidates", null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(sendOtp(null, counter, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(confirm(null, counter, "123456"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(post("/api/me/link/decline", null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------- HTTP

    private ResponseEntity<Map<String, Object>> get(String path, String bearerToken) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(bearerToken)), MAP);
    }

    private ResponseEntity<Map<String, Object>> sendOtp(String bearerToken, long customerId, String typedPhone) {
        String body = typedPhone == null ? "{\"customerId\": " + customerId + "}"
                : "{\"customerId\": " + customerId + ", \"phone\": \"" + typedPhone + "\"}";
        return post("/api/me/link/otp", bearerToken, body);
    }

    private ResponseEntity<Map<String, Object>> confirm(String bearerToken, long customerId, String code) {
        return post("/api/me/link/confirm", bearerToken,
                "{\"customerId\": " + customerId + ", \"code\": \"" + code + "\"}");
    }

    private ResponseEntity<Map<String, Object>> post(String path, String bearerToken, String json) {
        HttpHeaders headers = bearer(bearerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), MAP);
    }

    private static HttpHeaders bearer(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** Envelope lỗi đủ 6 trường, {@code traceId} khớp header (docs/api/00-method.md §3.5). */
    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp", "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value())
                .containsEntry("traceId", response.getHeaders().getFirst("X-Trace-Id"));
        assertThat((String) body.get("message")).isNotBlank();
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

    // ---------------------------------------------------------------- dữ liệu

    private long customerAccount(String email) {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, email);
    }

    private long onlineProfile(long accountId, String profilePhone, boolean pending) {
        return jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel, link_decision_pending)
                VALUES (?, 'Khách online', ?, 'ONLINE', ?) RETURNING id
                """, Long.class, accountId, profilePhone, pending);
    }

    private long counterProfile(String fullName, String profilePhone, String email) {
        return jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel)
                VALUES (?, ?, ?, 'COUNTER') RETURNING id
                """, Long.class, fullName, profilePhone, email);
    }

    private long pet(long customerId) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Mực', 'DOG', 'MALE') RETURNING id
                """, Long.class, customerId);
    }

    private String open(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "LinkProfileIT")).accessToken();
    }

    private String code() {
        return codeFor(counterEmail);
    }

    private String codeFor(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, String.class, email);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private long latestCodeId() {
        return jdbc.queryForObject("SELECT max(id) FROM otp_tokens WHERE purpose = 'LINK_PROFILE' AND target_email = ?",
                Long.class, counterEmail);
    }

    private int failedAttempts() {
        return jdbc.queryForObject("SELECT failed_attempts FROM otp_tokens WHERE id = ?", Integer.class,
                latestCodeId());
    }

    private long otpCount() {
        return jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE account_id = ? AND purpose = 'LINK_PROFILE'",
                Long.class, account);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ?", Long.class,
                counterEmail);
    }

    private boolean exists(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, customerId) == 1;
    }

    private boolean flag(long customerId) {
        return jdbc.queryForObject("SELECT link_decision_pending FROM customers WHERE id = ?", Boolean.class,
                customerId);
    }

    private Long linkedAccount(long customerId) {
        return jdbc.queryForObject("SELECT account_id FROM customers WHERE id = ?", Long.class, customerId);
    }

    private List<Map<String, Object>> audits(long customerId) {
        return jdbc.queryForList("""
                SELECT actor_account_id, actor_email, before_data::text AS before_data, after_data::text AS after_data
                FROM audit_logs WHERE action = 'CUSTOMER_PROFILE_LINKED' AND entity_id = ?""", customerId);
    }

    private static String newEmail() {
        return "link-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private static String randomPhone() {
        return "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    // ---------------------------------------------------------------- đồng thời, trigger

    /**
     * Giữ khóa dòng {@code accounts} ở một transaction khác, bắn {@code requests} request song song, đợi tất cả cùng chờ
     * khóa trên {@code pg_stat_activity}, rồi nhả — các request chạy tuần tự dưới khóa.
     */
    private <T> List<T> whileAccountLocked(long accountId, int requests, java.util.function.Supplier<T> action)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
            locked.countDown();
            await(release);
        }));
        await(locked);
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(CompletableFuture.supplyAsync(action));
        }
        waitUntilBlockedOnAccountLock(requests);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        List<T> results = new ArrayList<>();
        for (CompletableFuture<T> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    /** Hibernate 6 phát {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE} (CLAUDE.md, Gotchas). */
    private void waitUntilBlockedOnAccountLock(int expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from accounts%for no key update%'
                    """, Integer.class);
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("không đủ " + expected + " request chờ khóa dòng accounts");
    }

    private void withTrigger(String table, String timing, String condition, Runnable action) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_link_fail_" + suffix;
        String trigger = "trg_it_link_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN
                        RAISE EXCEPTION 'IT: lỗi giả lập';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, timing, table, function));
        try {
            action.run();
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Đứng thay module customer (BE-2) cho UC07, đúng các nghĩa vụ ghi ở javadoc {@link CustomerApi} và
     * {@link CustomerQueryApi}: SQL chạy trên connection của transaction identity (JpaTransactionManager), khóa
     * {@code customers}, kiểm lại dưới khóa, xóa {@code addresses} → hồ sơ online → gắn hồ sơ tại quầy. Các method không
     * thuộc UC07 ném như placeholder.
     */
    static final class JdbcCustomerModule implements CustomerApi, CustomerQueryApi {

        private final JdbcTemplate jdbc;
        volatile boolean failAfterDelete;
        volatile List<Object> lastLinkArgs;

        JdbcCustomerModule(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            failAfterDelete = false;
            lastLinkArgs = null;
        }

        @Override
        public Optional<CustomerContact> findContact(Long customerId) {
            return jdbc.query("""
                    SELECT id, full_name, phone, email, account_id, link_decision_pending FROM customers WHERE id = ?
                    """, (rs, n) -> new CustomerContact(rs.getLong("id"), rs.getString("full_name"),
                    rs.getString("phone"), rs.getString("email"), (Long) rs.getObject("account_id"),
                    rs.getBoolean("link_decision_pending")), customerId).stream().findFirst();
        }

        @Override
        public Optional<Long> findCustomerIdByAccountId(Long accountId) {
            return jdbc.queryForList("SELECT id FROM customers WHERE account_id = ?", Long.class, accountId).stream()
                    .findFirst();
        }

        @Override
        public List<LinkCandidate> findLinkCandidates(String phone) {
            return jdbc.query("""
                    SELECT id, full_name, email IS NOT NULL AS has_email FROM customers
                    WHERE created_channel = 'COUNTER' AND account_id IS NULL AND phone = ? ORDER BY id
                    """, (rs, n) -> new LinkCandidate(rs.getLong("id"), mask(rs.getString("full_name")),
                    rs.getBoolean("has_email")), phone);
        }

        @Override
        public OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId) {
            Map<String, Object> own = jdbc.queryForMap(
                    "SELECT id, created_channel FROM customers WHERE account_id = ?", accountId);
            if (!"ONLINE".equals(own.get("created_channel"))) {
                return OnlineProfileLinkability.ALREADY_LINKED;
            }
            return hasData((Long) own.get("id")) ? OnlineProfileLinkability.HAS_DATA : OnlineProfileLinkability.LINKABLE;
        }

        @Override
        public void linkAccountToCounterProfile(Long accountId, Long counterCustomerId, String expectedCounterEmail,
                String accountEmail) {
            lastLinkArgs = List.of(accountId, counterCustomerId, expectedCounterEmail, accountEmail);
            Map<String, Object> own = jdbc.queryForMap(
                    "SELECT id, created_channel FROM customers WHERE account_id = ? FOR UPDATE", accountId);
            Map<String, Object> target = jdbc.queryForMap(
                    "SELECT created_channel, account_id, email FROM customers WHERE id = ? FOR UPDATE",
                    counterCustomerId);
            long onlineId = (Long) own.get("id");
            if (!"ONLINE".equals(own.get("created_channel")) || hasData(onlineId)
                    || !"COUNTER".equals(target.get("created_channel")) || target.get("account_id") != null
                    || !expectedCounterEmail.equals(target.get("email"))) {
                throw new BusinessRuleViolationException("BR-TK-19", "Không thể liên kết hồ sơ này");
            }
            jdbc.update("DELETE FROM addresses WHERE customer_id = ?", onlineId);
            jdbc.update("DELETE FROM customers WHERE id = ?", onlineId);
            if (failAfterDelete) {
                throw new BusinessRuleViolationException("BR-TK-19", "Lỗi giả lập sau khi xóa");
            }
            jdbc.update("UPDATE customers SET account_id = ?, email = ? WHERE id = ?", accountId, accountEmail,
                    counterCustomerId);
        }

        @Override
        public void declineLink(Long accountId) {
            jdbc.update("""
                    UPDATE customers SET link_decision_pending = false
                    WHERE account_id = ? AND created_channel = 'ONLINE'""", accountId);
        }

        @Override
        public Long createOnlineProfile(Long accountId, String fullName, String phone) {
            throw new UnsupportedOperationException("ngoài UC07");
        }

        @Override
        public boolean flagLinkDecisionIfPhoneMatches(Long accountId) {
            throw new UnsupportedOperationException("ngoài UC07");
        }

        @Override
        public void deleteOnlineProfileOfUnverifiedAccount(Long accountId) {
            throw new UnsupportedOperationException("ngoài UC07");
        }

        private boolean hasData(long customerId) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pets WHERE customer_id = ?)
                        OR EXISTS (SELECT 1 FROM appointments WHERE customer_id = ?)
                        OR EXISTS (SELECT 1 FROM boarding_bookings WHERE customer_id = ?)
                        OR EXISTS (SELECT 1 FROM orders WHERE customer_id = ?)
                        OR EXISTS (SELECT 1 FROM feedbacks WHERE customer_id = ?)
                    """, Boolean.class, customerId, customerId, customerId, customerId, customerId));
        }

        /** "Nguyễn Văn An" → "Ng*** V** A*": như ví dụ của BR-TK-19, đủ để test không thấy họ tên thật. */
        private static String mask(String fullName) {
            String[] words = fullName.split(" ");
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                int keep = i == 0 ? Math.min(2, word.length()) : 1;
                masked.append(i == 0 ? "" : " ").append(word, 0, keep)
                        .append("*".repeat(i == 0 ? 3 : i == words.length - 1 ? 1 : 2));
            }
            return masked.toString();
        }
    }
}
