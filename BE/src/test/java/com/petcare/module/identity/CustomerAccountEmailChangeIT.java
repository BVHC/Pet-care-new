package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;
import com.zaxxer.hikari.HikariDataSource;

/**
 * UC22 — lễ tân sửa hộ email tài khoản khách tại quầy: {@code POST /api/customers/{customerId}/account-email-change}
 * (#25) và {@code …/confirm} (#26) qua HTTP trên Postgres 17 thật (identity-v1; BR-TK-01, 04…07, 16, BR-KH-10). Dùng
 * module customer THẬT của BE-2 ({@code CustomerQueryService}), không có stand-in như {@code LinkProfileIT}: hồ sơ
 * khách dựng bằng SQL, đường tra hồ sơ → tài khoản chạy qua {@code CustomerQueryApi}. Khuôn {@code StaffEmailChangeIT};
 * {@link MutableClock} điều khiển hạn mã và quota BR-TK-07. Mỗi test tự tạo dữ liệu với email ngẫu nhiên.
 * <p>
 * Tài khoản khách {@code DISABLED} không tồn tại (CHECK {@code ck_accounts_disabled_staff_only}, BR-QT-07), nên "mọi
 * trạng thái được phép" ở đây là {@code ACTIVE} có {@code is_locked}; {@code PENDING} bị chặn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CustomerAccountEmailChangeIT.TestBeans.class})
@ExtendWith(OutputCaptureExtension.class)
class CustomerAccountEmailChangeIT {

    /** 03:00 UTC = 10:00 giờ Việt Nam. */
    private static final Instant T0 = Instant.parse("2026-10-12T03:00:00Z");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "Petcare123";
    private static final String METHOD = "ID_CARD_IN_PERSON";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String NOT_LINKED_MESSAGE = "Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa";
    private static final String PENDING_MESSAGE = "Tài khoản chưa xác thực email nên chưa sửa được email";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    /** Hồ sơ khách và tài khoản gắn với nó; {@code email} là email hiện tại của tài khoản. */
    private record Customer(long accountId, long customerId, String email) {
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
    private PasswordEncoder passwordEncoder;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long receptionist;
    private String receptionistToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        tag = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch();
        receptionist = staff("RECEPTIONIST", branchA);
        receptionistToken = token(receptionist);
    }

    // ================================================================ #25 gửi mã

    @Test
    void startSendsCodeOnlyToNormalizedNewEmailAndReturnsContractShape(CapturedOutput output) {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();

        assertError(start(receptionistToken, customer.customerId(), " " + newEmail + " "), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        ResponseEntity<Map<String, Object>> response = start(receptionistToken, customer.customerId(),
                newEmail.toUpperCase());
        Map<String, Object> data = map(ok(response));

        assertThat(response.getBody().get("message")).isEqualTo("Đã gửi mã xác thực tới email mới");
        assertThat(data).containsOnlyKeys("resendAvailableAt", "maskedEmail", "codeLength")
                .containsEntry("maskedEmail", null).containsEntry("codeLength", 6);
        assertThat(Instant.parse((String) data.get("resendAvailableAt"))).isEqualTo(T0.plusSeconds(60));
        String code = code(newEmail);
        assertThat(code).matches("\\d{6}").hasSize((Integer) data.get("codeLength"));
        assertThat(data.toString()).doesNotContain(code);
        assertThat(output.getAll()).doesNotContain(code);

        List<Map<String, Object>> codes = otps(customer.accountId());
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0)).containsEntry("target_email", newEmail).containsEntry("customer_id", null)
                .containsEntry("failed_attempts", 0).containsEntry("consumed_at", null);
        assertThat(email(customer.accountId())).as("BR-TK-16: chưa xác thực thì giữ email cũ")
                .isEqualTo(customer.email());
        assertThat(outboxTo(customer.email(), "OTP_EMAIL_CHANGE")).as("mã chỉ tới email mới").isZero();
        assertThat(outboxTo(newEmail, "OTP_EMAIL_CHANGE")).isEqualTo(1);
        assertThat(auditsOn(customer.accountId())).as("gửi mã không audit").isZero();
        assertPayloadMatchesTemplate("OTP_EMAIL_CHANGE", newEmail);
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'thoi_han_phut' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_EMAIL_CHANGE'""", String.class, newEmail))
                .isEqualTo("5");
        assertRowNotLocked(customer.accountId());
    }

    @Test
    void sameAsCurrentEmailIsBrTk01WithOwnMessage() {
        Customer customer = onlineCustomer();

        ResponseEntity<Map<String, Object>> response = start(receptionistToken, customer.customerId(),
                customer.email().toUpperCase());

        assertRule(response, "BR-TK-01");
        assertThat(response.getBody().get("message")).isEqualTo("Email mới trùng email hiện tại (BR-TK-01)");
        assertThat(otps(customer.accountId())).isEmpty();
        assertThat(outboxTo(customer.email(), "OTP_EMAIL_CHANGE")).isZero();
    }

    @Test
    void emailOfAnotherAccountIncludingPendingAndStaffIsBrTk01() {
        Customer customer = onlineCustomer();
        Customer pending = customer("PENDING", false, "ONLINE", "hash");
        Customer other = onlineCustomer();

        for (String taken : List.of(pending.email(), other.email(), email(receptionist))) {
            ResponseEntity<Map<String, Object>> response = start(receptionistToken, customer.customerId(), taken);
            assertRule(response, "BR-TK-01");
            assertThat(response.getBody().get("message")).isEqualTo("Email đã được sử dụng (BR-TK-01)");
            assertThat(outboxTo(taken, "OTP_EMAIL_CHANGE")).isZero();
        }
        assertThat(otps(customer.accountId())).isEmpty();
    }

    /** BR-TK-07: 60 s giữa hai lần gửi tới cùng email; mã thứ 6 trong 1 giờ bị chặn; mã mới hủy mã cũ. */
    @Test
    void resendQuotaFollowsTargetEmailAndNewCodeCancelsOld() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();

        ok(start(receptionistToken, customer.customerId(), newEmail));
        String firstCode = code(newEmail);
        clock.advance(Duration.ofSeconds(30));
        assertRule(start(receptionistToken, customer.customerId(), newEmail), "BR-TK-07");
        assertThat(otps(customer.accountId())).hasSize(1);

        clock.advance(Duration.ofSeconds(31));
        ok(start(receptionistToken, customer.customerId(), newEmail));
        assertThat(otps(customer.accountId())).hasSize(2);
        assertThat(otps(customer.accountId()).get(0).get("invalidated_at")).as("mã cũ bị hủy").isNotNull();
        if (!firstCode.equals(code(newEmail))) {
            assertRule(confirm(receptionistToken, customer.customerId(), firstCode), "BR-TK-05");
        }

        for (int sent = 3; sent <= 5; sent++) {
            clock.advance(Duration.ofSeconds(61));
            ok(start(receptionistToken, customer.customerId(), newEmail));
        }
        clock.advance(Duration.ofSeconds(61));
        assertRule(start(receptionistToken, customer.customerId(), newEmail), "BR-TK-07");

        List<Map<String, Object>> codes = otps(customer.accountId());
        assertThat(codes).hasSize(5);
        assertThat(codes.subList(0, 4)).allSatisfy(row -> assertThat(row.get("invalidated_at")).isNotNull());
        assertThat(codes.get(4).get("invalidated_at")).as("BR-TK-05: chỉ mã mới nhất còn sống").isNull();
    }

    /** Mã mới của tài khoản hủy mã cũ của chính tài khoản đó, kể cả khi đổi sang email khác (khuôn ADR-0034 Q3). */
    @Test
    void newCodeForAnotherEmailCancelsOwnPreviousCode() {
        Customer customer = onlineCustomer();
        String first = newEmail();
        String second = newEmail();
        ok(start(receptionistToken, customer.customerId(), first));
        String firstCode = code(first);
        ok(start(receptionistToken, customer.customerId(), second));

        ResponseEntity<Map<String, Object>> stale = confirm(receptionistToken, customer.customerId(), firstCode);
        if (!firstCode.equals(code(second))) {
            assertRule(stale, "BR-TK-05");
        }
        assertThat(email(customer.accountId())).isNotEqualTo(first);
        assertThat(otps(customer.accountId()).get(0).get("invalidated_at")).isNotNull();

        noContent(confirm(receptionistToken, customer.customerId(), code(second)));
        assertThat(email(customer.accountId())).isEqualTo(second);
    }

    // ================================================================ #26 xác nhận — thành công

    @Test
    void confirmChangesEmailNotifiesOldAndNewAuditsOnceAndKeepsSession() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        String customerToken = token(customer.accountId());
        long openSessions = openSessions(customer.accountId());
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String code = code(newEmail);

        ResponseEntity<Map<String, Object>> response = confirm(receptionistToken, customer.customerId(), code);

        noContent(response);
        assertThat(email(customer.accountId())).isEqualTo(newEmail);
        assertThat(otps(customer.accountId()).get(0).get("consumed_at")).isNotNull();

        List<String> recipients = jdbc.queryForList("""
                SELECT recipient_email FROM notification_outbox
                WHERE template_code = 'EMAIL_CHANGED_NOTICE' AND payload->>'email_moi' = ?""", String.class, newEmail);
        assertThat(recipients).as("đúng hai thư: email cũ và email mới").containsExactlyInAnyOrder(
                customer.email(), newEmail);
        for (String recipient : recipients) {
            Map<String, Object> notice = jdbc.queryForMap("""
                    SELECT payload->>'email_cu' AS email_cu, payload->>'email_moi' AS email_moi,
                           payload->>'thoi_diem' AS thoi_diem
                    FROM notification_outbox WHERE recipient_email = ? AND template_code = 'EMAIL_CHANGED_NOTICE'""",
                    recipient);
            assertThat(notice).containsEntry("email_cu", customer.email()).containsEntry("email_moi", newEmail)
                    .containsEntry("thoi_diem", "10:00 12/10/2026");
            assertPayloadMatchesTemplate("EMAIL_CHANGED_NOTICE", recipient);
        }

        List<Map<String, Object>> audits = audits(receptionist, "CUSTOMER_EMAIL_CHANGED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("entity_type", "accounts")
                .containsEntry("entity_id", customer.accountId()).containsEntry("actor_email", email(receptionist));
        assertThat(audits.get(0).get("before_data")).isEqualTo("{\"email\": \"" + customer.email() + "\"}");
        assertThat(audits.get(0).get("after_data"))
                .isEqualTo("{\"email\": \"" + newEmail + "\", \"verificationMethod\": \"" + METHOD + "\"}");
        assertThat(audits.get(0).toString()).doesNotContain(code).doesNotContainIgnoringCase("hash");
        assertThat(auditsOn(customer.accountId())).isEqualTo(1);

        assertThat(openSessions(customer.accountId())).as("không hủy phiên của khách").isEqualTo(openSessions);
        assertThat(map(ok(get("/api/me", customerToken))).get("account"))
                .satisfies(account -> assertThat(map(account)).containsEntry("email", newEmail));
        assertRowNotLocked(customer.accountId());
    }

    /** Email là định danh (BR-TK-01): sau khi đổi, đăng nhập bằng email mới, email cũ như email lạ; mật khẩu giữ nguyên. */
    @Test
    void afterChangeLoginFollowsTheNewEmail() {
        Customer customer = customer("ACTIVE", false, "ONLINE", passwordEncoder.encode(PASSWORD));
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        noContent(confirm(receptionistToken, customer.customerId(), code(newEmail)));

        assertError(login(customer.email(), PASSWORD), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertThat(login(newEmail, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** ACTIVE + đang bị khóa vẫn đổi được (khôi phục tài khoản); khóa, trạng thái, hồ sơ COUNTER đã gắn đều không đổi. */
    @ParameterizedTest(name = "channel={0} locked={1}")
    @CsvSource({"ONLINE, true", "COUNTER, false", "COUNTER, true"})
    void lockedOrCounterLinkedAccountCanChangeEmailAndKeepsStatusAndLock(String channel, boolean locked) {
        Customer customer = customer("ACTIVE", locked, channel, "hash");
        String newEmail = newEmail();

        ok(start(receptionistToken, customer.customerId(), newEmail));
        noContent(confirm(receptionistToken, customer.customerId(), code(newEmail)));

        assertThat(jdbc.queryForMap("SELECT email, status, is_locked FROM accounts WHERE id = ?",
                customer.accountId())).containsEntry("email", newEmail).containsEntry("status", "ACTIVE")
                .containsEntry("is_locked", locked);
    }

    /**
     * Khoảng trống đã biết, ghi lại để không ai tưởng đã xong: đổi email tài khoản KHÔNG đồng bộ {@code customers.email}
     * (module customer của BE-2; docs/adr/0034 mục TBD, BR-KH-01 "email lấy theo tài khoản"). Khi BE-2 đồng bộ thì
     * test này phải đổi theo.
     */
    @Test
    void customerProfileEmailIsNotSyncedKnownGap() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));

        noContent(confirm(receptionistToken, customer.customerId(), code(newEmail)));

        assertThat(email(customer.accountId())).isEqualTo(newEmail);
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class,
                customer.customerId())).isEqualTo(customer.email());
    }

    // ================================================================ #26 xác nhận — mã sai / hết hạn / email bị lấy

    @Test
    void wrongCodeIsCountedAndCommittedFifthCancelsTheCode() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String right = code(newEmail);
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertRule(confirm(receptionistToken, customer.customerId(), wrong), "BR-TK-05");
            assertThat(otps(customer.accountId()).get(0).get("failed_attempts")).as("commit sau lần sai %d", attempt)
                    .isEqualTo(attempt);
        }
        assertRule(confirm(receptionistToken, customer.customerId(), wrong), "BR-TK-06");
        assertThat(otps(customer.accountId()).get(0).get("invalidated_at")).isNotNull();
        assertRule(confirm(receptionistToken, customer.customerId(), right), "BR-TK-05");
        assertThat(email(customer.accountId())).isEqualTo(customer.email());
        assertThat(audits(receptionist, "CUSTOMER_EMAIL_CHANGED")).isEmpty();
    }

    @Test
    void expiredCodeIsRejectedWithoutCounting() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));

        clock.advance(Duration.ofMinutes(5));
        assertRule(confirm(receptionistToken, customer.customerId(), code(newEmail)), "BR-TK-05");

        assertThat(otps(customer.accountId()).get(0)).containsEntry("failed_attempts", 0)
                .containsEntry("consumed_at", null);
        assertThat(email(customer.accountId())).isEqualTo(customer.email());
    }

    @Test
    void confirmWithoutCodeAndSecondConfirmAreBrTk05() {
        Customer customer = onlineCustomer();
        assertRule(confirm(receptionistToken, customer.customerId(), "123456"), "BR-TK-05");

        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String code = code(newEmail);
        noContent(confirm(receptionistToken, customer.customerId(), code));
        assertRule(confirm(receptionistToken, customer.customerId(), code), "BR-TK-05");

        assertThat(audits(receptionist, "CUSTOMER_EMAIL_CHANGED")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE template_code = 'EMAIL_CHANGED_NOTICE'
                  AND payload->>'email_moi' = ?""", Long.class, newEmail)).isEqualTo(2);
    }

    /** Email mới vừa bị tài khoản khác lấy sau khi gửi mã: BR-TK-01, rollback cả {@code consumed_at}, mã dùng lại được. */
    @Test
    void emailTakenAfterCodeSentIsBrTk01AndCodeStaysUsable() {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String code = code(newEmail);
        long interloper = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE')
                RETURNING id""", Long.class, newEmail);

        ResponseEntity<Map<String, Object>> response = confirm(receptionistToken, customer.customerId(), code);

        assertRule(response, "BR-TK-01");
        assertThat(response.getBody().get("message")).isEqualTo("Email đã được sử dụng (BR-TK-01)");
        assertThat(otps(customer.accountId()).get(0)).containsEntry("consumed_at", null)
                .containsEntry("failed_attempts", 0);
        assertThat(email(customer.accountId())).isEqualTo(customer.email());
        assertThat(outboxTo(customer.email(), "EMAIL_CHANGED_NOTICE")).isZero();
        assertThat(audits(receptionist, "CUSTOMER_EMAIL_CHANGED")).isEmpty();

        jdbc.update("DELETE FROM accounts WHERE id = ?", interloper);
        noContent(confirm(receptionistToken, customer.customerId(), code));
        assertThat(email(customer.accountId())).isEqualTo(newEmail);
    }

    // ================================================================ phân quyền, 404, hồ sơ chưa gắn, hình thức

    /** Chỉ RECEPTIONIST (BR-TK-16); body hợp lệ để lỗi 400 không che 403. Khách gọi thẳng hồ sơ của chính mình cũng 403. */
    @ParameterizedTest(name = "{0} → 403")
    @CsvSource({"VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOtherThanReceptionistAre403OnBothEndpoints(String role) {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String code = code(newEmail);
        String actorToken = token("CUSTOMER".equals(role) ? customer.accountId() : staff(role, branchOf(role)));
        String otherEmail = newEmail();

        assertError(start(actorToken, customer.customerId(), otherEmail), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(confirm(actorToken, customer.customerId(), code), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        assertThat(outboxTo(otherEmail, "OTP_EMAIL_CHANGE")).isZero();
        assertThat(otps(customer.accountId())).hasSize(1);
        assertThat(otps(customer.accountId()).get(0)).containsEntry("failed_attempts", 0)
                .containsEntry("consumed_at", null).containsEntry("invalidated_at", null);
        assertThat(email(customer.accountId())).isEqualTo(customer.email());
        assertThat(audits(receptionist, "STAFF_ACTION_DENIED")).isEmpty();
    }

    @Test
    void noTokenIs401AndReceptionistStillOnFirstPasswordIsBrTk17() {
        Customer customer = onlineCustomer();
        assertError(start(null, customer.customerId(), newEmail()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(confirm(null, customer.customerId(), "123456"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);
        assertRule(start(receptionistToken, customer.customerId(), newEmail()), "BR-TK-17");
        assertThat(otps(customer.accountId())).isEmpty();
    }

    /** Bean Validation 400 đứng trước {@code @PreAuthorize} 403: token VET + body sai vẫn là 400. */
    @Test
    void invalidBodiesAre400BeforeTheRoleCheck() {
        Customer customer = onlineCustomer();
        String vetToken = token(staff("VET", branchA));
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60) + ".vn";
        String path = path(customer.customerId());

        assertThat(startValidationMessage(vetToken, path, "{}")).contains("newEmail: Email mới không được để trống")
                .contains("verificationMethod: Phương thức xác minh không được để trống");
        assertThat(startValidationMessage(vetToken, path, "{\"newEmail\":\"\",\"verificationMethod\":\"" + METHOD
                + "\"}")).contains("newEmail: Email mới không được để trống");
        assertThat(startValidationMessage(vetToken, path, "{\"newEmail\":\"khong-hop-le\",\"verificationMethod\":\""
                + METHOD + "\"}")).contains("newEmail: Email mới không đúng định dạng");
        assertThat(startValidationMessage(vetToken, path, "{\"newEmail\":\"" + tooLong + "\",\"verificationMethod\":\""
                + METHOD + "\"}")).contains("newEmail: Email mới tối đa 255 ký tự");
        assertThat(startValidationMessage(vetToken, path, "{\"newEmail\":\"a@b.vn\"}"))
                .contains("verificationMethod: Phương thức xác minh không được để trống");
        assertThat(startValidationMessage(vetToken, path, "{\"newEmail\":\"a@b.vn\",\"verificationMethod\":null}"))
                .contains("verificationMethod: Phương thức xác minh không được để trống");
        for (String body : List.of("{}", "{\"code\":\"\"}", "{\"code\":\"12a456\"}", "{\"code\":\"123456789\"}")) {
            assertError(raw(path + "/confirm", vetToken, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertThat(otps(customer.accountId())).isEmpty();
    }

    /** Giá trị lạ của enum là lỗi đọc JSON (MALFORMED_REQUEST), không phải VALIDATION_FAILED; lỗi hình thức không tiêu lượt. */
    @Test
    void unknownVerificationMethodAndBrokenInputAreMalformedAndDoNotCountAnAttempt() {
        Customer customer = onlineCustomer();
        String path = path(customer.customerId());

        assertError(raw(path, receptionistToken, "{\"newEmail\":\"a@b.vn\",\"verificationMethod\":\"PASSPORT\"}"),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(path, receptionistToken, "{\"newEmail\":"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw("/api/customers/abc/account-email-change", receptionistToken,
                "{\"newEmail\":\"a@b.vn\",\"verificationMethod\":\"" + METHOD + "\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(otps(customer.accountId())).isEmpty();

        ok(start(receptionistToken, customer.customerId(), newEmail()));
        assertError(raw(path + "/confirm", receptionistToken, "{\"code\":\"12a456\"}"), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(otps(customer.accountId()).get(0).get("failed_attempts")).as("lỗi hình thức không tiêu lượt nhập")
                .isEqualTo(0);
    }

    @Test
    void missingProfileIs404OnBothEndpoints() {
        assertError(start(receptionistToken, 987654321L, newEmail()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        ResponseEntity<Map<String, Object>> confirm = confirm(receptionistToken, 987654321L, "123456");
        assertError(confirm, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(confirm.getBody().get("message")).isEqualTo("Không tìm thấy hồ sơ khách #987654321");
    }

    /** Hồ sơ COUNTER chưa gắn tài khoản: 400 không mã BR (hợp đồng), ở cả hai endpoint, trước khi đụng OTP. */
    @Test
    void unlinkedCounterProfileIs400WithoutRuleCodeOnBothEndpoints() {
        long counter = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel)
                VALUES (?, '0912345678', ?, 'COUNTER') RETURNING id""", Long.class, "Khách quầy " + tag, newEmail());
        String newEmail = newEmail();

        assertNotRuleError(start(receptionistToken, counter, newEmail), NOT_LINKED_MESSAGE);
        assertNotRuleError(confirm(receptionistToken, counter, "123456"), NOT_LINKED_MESSAGE);

        assertThat(outboxTo(newEmail, "OTP_EMAIL_CHANGE")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_tokens WHERE target_email = ?", Long.class,
                newEmail)).isZero();
    }

    /** Tài khoản PENDING chưa xác thực email: không đổi được (BR-TK-08); cùng kiểu 400 không mã BR. */
    @Test
    void pendingAccountIs400WithoutRuleCodeOnStartAndConfirm() {
        Customer pending = customer("PENDING", false, "ONLINE", "hash");
        String newEmail = newEmail();

        assertNotRuleError(start(receptionistToken, pending.customerId(), newEmail), PENDING_MESSAGE);
        assertNotRuleError(confirm(receptionistToken, pending.customerId(), "123456"), PENDING_MESSAGE);

        assertThat(otps(pending.accountId())).isEmpty();
        assertThat(outboxTo(newEmail, "OTP_EMAIL_CHANGE")).isZero();
        assertThat(email(pending.accountId())).isEqualTo(pending.email());
    }

    /**
     * Lễ tân không được sửa email nhân viên qua cửa này: hồ sơ trỏ nhầm vào tài khoản nhân viên (dữ liệu hỏng, nhưng
     * CHECK của schema cho phép) → 404 như hồ sơ không tồn tại, kể cả khi nhân viên đang có mã đổi email còn mở.
     */
    @Test
    void profileLinkedToStaffAccountIs404AndNeverTouchesStaffEmail() {
        long vet = staff("VET", branchA);
        String vetEmail = email(vet);
        String staffNewEmail = newEmail();
        ok(post("/api/staff/" + vet + "/email-change", token(staff("SUPER_MANAGER", null)),
                Map.of("newEmail", staffNewEmail)));
        String staffCode = code(staffNewEmail);
        long profile = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, '0912345678', 'COUNTER') RETURNING id""", Long.class, vet, "Hồ sơ hỏng " + tag);
        String otherEmail = newEmail();

        assertError(start(receptionistToken, profile, otherEmail), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(confirm(receptionistToken, profile, staffCode), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");

        assertThat(email(vet)).isEqualTo(vetEmail);
        assertThat(otps(vet)).hasSize(1);
        assertThat(otps(vet).get(0)).containsEntry("consumed_at", null).containsEntry("failed_attempts", 0)
                .containsEntry("invalidated_at", null);
        assertThat(outboxTo(otherEmail, "OTP_EMAIL_CHANGE")).isZero();
        assertThat(outboxTo(vetEmail, "EMAIL_CHANGED_NOTICE")).isZero();
    }

    // ================================================================ commit / rollback

    @Test
    void outboxFailureOnStartRollsBackCodeAndKeepsPreviousOne() {
        Customer customer = onlineCustomer();
        String first = newEmail();
        String second = newEmail();
        ok(start(receptionistToken, customer.customerId(), first));

        withTrigger("notification_outbox", "INSERT", "NEW.recipient_email = '" + second + "'", () ->
                assertError(start(receptionistToken, customer.customerId(), second),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(otps(customer.accountId())).hasSize(1);
        assertThat(otps(customer.accountId()).get(0).get("invalidated_at")).as("mã cũ còn sống").isNull();
        ok(start(receptionistToken, customer.customerId(), second));
        assertThat(otps(customer.accountId())).as("lần lỗi không tiêu quota").hasSize(2);
    }

    @ParameterizedTest(name = "lỗi INSERT {0}")
    @CsvSource({"audit_logs, NEW.action = 'CUSTOMER_EMAIL_CHANGED'",
            "notification_outbox, NEW.template_code = 'EMAIL_CHANGED_NOTICE'"})
    void failureAfterSettleRollsBackEmailCodeNoticesAndAudit(String table, String condition) {
        Customer customer = onlineCustomer();
        String newEmail = newEmail();
        ok(start(receptionistToken, customer.customerId(), newEmail));
        String code = code(newEmail);

        withTrigger(table, "INSERT", condition, () ->
                assertError(confirm(receptionistToken, customer.customerId(), code), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(email(customer.accountId())).isEqualTo(customer.email());
        assertThat(otps(customer.accountId()).get(0)).containsEntry("consumed_at", null)
                .containsEntry("failed_attempts", 0);
        assertThat(outboxTo(customer.email(), "EMAIL_CHANGED_NOTICE")).isZero();
        assertThat(audits(receptionist, "CUSTOMER_EMAIL_CHANGED")).isEmpty();
        assertRowNotLocked(customer.accountId());
        noContent(confirm(receptionistToken, customer.customerId(), code));
        assertThat(email(customer.accountId())).as("mã vẫn dùng lại được").isEqualTo(newEmail);
    }

    /** Quyết định 4: BCrypt (sinh mã, so mã) chạy khi không giữ connection nào (docs/adr/0023). */
    @Test
    void bcryptRunsWithoutHoldingAConnection() {
        Customer customer = onlineCustomer();
        List<Integer> active = new ArrayList<>();
        doAnswer(invocation -> {
            Object raw = invocation.getArgument(0);
            if (raw.toString().matches("\\d{6}")) {
                active.add(hikari().getHikariPoolMXBean().getActiveConnections());
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());
        doAnswer(invocation -> {
            active.add(hikari().getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
        String newEmail = newEmail();

        ok(start(receptionistToken, customer.customerId(), newEmail));
        noContent(confirm(receptionistToken, customer.customerId(), code(newEmail)));

        assertThat(active).hasSize(2).containsOnly(0);
    }

    // ================================================================ helpers — dữ liệu

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "CustomerAccountEmailChangeIT " + UUID.randomUUID());
    }

    private Long branchOf(String role) {
        return switch (role) {
            case "SUPER_MANAGER", "ADMIN" -> null;
            default -> branchA;
        };
    }

    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag + " " + id, branchId);
        return id;
    }

    private Customer onlineCustomer() {
        return customer("ACTIVE", false, "ONLINE", "hash");
    }

    /**
     * Tài khoản khách (không SĐT — CHECK {@code ck_accounts_phone_by_role}; PENDING có {@code pending_expires_at}) và
     * hồ sơ gắn với nó (email hồ sơ = email tài khoản, BR-KH-01). Hồ sơ COUNTER bắt buộc có SĐT.
     */
    private Customer customer(String status, boolean locked, String channel, String passwordHash) {
        String email = newEmail();
        long accountId = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status, is_locked, pending_expires_at)
                VALUES (?, ?, 'CUSTOMER', ?, ?,
                        CASE WHEN CAST(? AS text) = 'PENDING' THEN now() + interval '24 hours' END)
                RETURNING id""", Long.class, email, passwordHash, status, locked, status);
        long customerId = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, email, created_channel)
                VALUES (?, ?, '0912345678', ?, ?) RETURNING id""", Long.class, accountId, "Khách " + tag, email,
                channel);
        return new Customer(accountId, customerId, email);
    }

    private static String newEmail() {
        return "customer-email-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "CustomerAccountEmailChangeIT"))
                .accessToken();
    }

    private long openSessions(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId);
    }

    /** Mã trong email OTP mới nhất tới {@code email} (payload outbox trước khi ST20 gửi — job tắt ở profile test). */
    private String code(String email) {
        return jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox
                WHERE recipient_email = ? AND template_code = 'OTP_EMAIL_CHANGE' ORDER BY id DESC LIMIT 1""",
                String.class, email);
    }

    private List<Map<String, Object>> otps(long accountId) {
        return jdbc.queryForList("""
                SELECT target_email, customer_id, failed_attempts, consumed_at, invalidated_at FROM otp_tokens
                WHERE account_id = ? AND purpose = 'CHANGE_EMAIL' ORDER BY id""", accountId);
    }

    private long outboxTo(String email, String template) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_outbox WHERE recipient_email = ? AND template_code = ?""",
                Long.class, email, template);
    }

    /** BR-QT-14: khóa payload ⊆ {@code allowed_vars} và ⊇ {@code required_vars} của chính dòng mẫu. */
    private void assertPayloadMatchesTemplate(String template, String recipient) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM jsonb_object_keys(o.payload) k
                         WHERE NOT jsonb_exists(t.allowed_vars, k)) AS not_allowed,
                       (SELECT count(*) FROM jsonb_array_elements_text(t.required_vars) r
                         WHERE NOT jsonb_exists(o.payload, r)) AS missing
                FROM notification_outbox o JOIN notification_templates t ON t.code = o.template_code
                WHERE o.recipient_email = ? AND o.template_code = ? ORDER BY o.id DESC LIMIT 1""", recipient,
                template);
        assertThat(row).as("%s", row).containsEntry("not_allowed", 0L).containsEntry("missing", 0L);
    }

    private List<Map<String, Object>> audits(long actorId, String action) {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, actor_email, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE actor_account_id = ? AND action = ? ORDER BY id""", actorId, action);
    }

    private long auditsOn(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'accounts' AND entity_id = ?",
                Long.class, accountId);
    }

    /** Không còn giao dịch nào giữ khóa dòng {@code accounts} (lỗi / thành công đều phải nhả khóa). */
    private void assertRowNotLocked(long accountId) {
        tx.executeWithoutResult(status -> jdbc.queryForObject(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, accountId));
    }

    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_customer_email_fail_" + suffix;
        String trigger = "trg_it_customer_email_fail_" + suffix;
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

    private HikariDataSource hikari() {
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ================================================================ helpers — HTTP

    private static String path(long customerId) {
        return "/api/customers/" + customerId + "/account-email-change";
    }

    private ResponseEntity<Map<String, Object>> start(String token, long customerId, String newEmail) {
        return post(path(customerId), token, Map.of("newEmail", newEmail, "verificationMethod", METHOD));
    }

    private ResponseEntity<Map<String, Object>> confirm(String token, long customerId, String code) {
        return post(path(customerId) + "/confirm", token, Map.of("code", code));
    }

    private String startValidationMessage(String token, String path, String body) {
        ResponseEntity<Map<String, Object>> response = raw(path, token, body);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        return (String) response.getBody().get("message");
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

    /** 204 không body (identity-v1 #26; docs/api/00-method.md §3.4). */
    private static void noContent(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    /** 400 {@code VALIDATION_FAILED} với message nguyên văn, không có mã {@code BR-*} (khuôn #38 "Vui lòng chọn chi nhánh"). */
    private static void assertNotRuleError(ResponseEntity<Map<String, Object>> response, String message) {
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat((String) response.getBody().get("message")).isEqualTo(message).doesNotContain("BR-");
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
