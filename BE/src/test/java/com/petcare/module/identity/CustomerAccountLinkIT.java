package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;

/**
 * UC22 — lễ tân liên kết HỘ tài khoản online với hồ sơ tại quầy: {@code POST /api/customers/{customerId}/link-account}
 * (identity-v1 #27; BR-TK-11, 16, 19) qua HTTP trên Postgres 17 thật, với module customer THẬT của BE-2 (không có
 * stand-in như {@code LinkProfileIT}): hồ sơ, địa chỉ, thú cưng dựng bằng SQL; các lệnh khóa / kiểm lại / xóa / gắn chạy
 * qua {@code CustomerApi}, {@code CustomerQueryApi}. Không OTP: lễ tân đã đối chiếu CCCD, nên mọi lỗi và mọi ca thành công
 * đều phải để lại đúng dấu vết (không thư, không hủy phiên, đúng MỘT dòng audit khi thành công, không gì khi thất bại).
 * Mỗi test tự tạo dữ liệu với email ngẫu nhiên.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerAccountLinkIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String METHOD = "ID_CARD_IN_PERSON";
    private static final String PHONE = "0912345678";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private static final String ACCOUNT_NOT_FOUND = "Không tìm thấy tài khoản khách hàng đã kích hoạt với email này";
    private static final String PROFILE_HAS_ACCOUNT = "Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết)";
    private static final String PROFILE_NO_EMAIL = "Hồ sơ tại quầy chưa có email. Hãy bổ sung email vào hồ sơ (sửa hồ sơ"
            + " khách) rồi liên kết lại";
    private static final String ACCOUNT_LINKED = "Tài khoản đã được liên kết với một hồ sơ tại quầy khác";
    private static final String ONLINE_HAS_DATA = "Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng"
            + " hoặc góp ý nên không liên kết được";
    private static final String ACCOUNT_LOCKED = "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ";
    private static final String NOT_LINKABLE_ANYMORE = "Hồ sơ tại quầy không còn liên kết được";

    /** Tài khoản khách và hồ sơ của nó; {@code email} là email tài khoản. */
    private record Online(long accountId, long profileId, String email) {
    }

    /** Hồ sơ tại quầy; {@code email} có thể {@code null}. */
    private record Counter(long id, String email) {
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String tag;
    private long branchA;
    private long receptionist;
    private String receptionistToken;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branchA = branch();
        receptionist = staff("RECEPTIONIST", branchA);
        receptionistToken = token(receptionist);
    }

    // ================================================================ (a) thành công

    @Test
    void linkMovesTheAccountToTheCounterProfileDeletesTheOnlineOneAndAuditsExactlyOnce() {
        Online online = online("ACTIVE", false);
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", online.profileId());
        address(online.profileId());
        address(online.profileId());
        Counter counter = counter("tam-" + UUID.randomUUID() + "@petcare.test");
        String customerToken = token(online.accountId());
        long openSessions = openSessions(online.accountId());

        ResponseEntity<Map<String, Object>> response = link(receptionistToken, counter.id(),
                online.email().toUpperCase());

        Map<String, Object> data = map(ok(response));
        assertThat(response.getBody().get("message")).isEqualTo("Đã liên kết hồ sơ với tài khoản");
        assertThat(data).containsOnlyKeys("customerId");
        assertThat(number(data.get("customerId"))).isEqualTo(counter.id());

        assertThat(exists(online.profileId())).as("hồ sơ online bị xóa").isFalse();
        assertThat(addressCount(online.profileId())).as("sổ địa chỉ online xóa cùng hồ sơ (ADR-0027)").isZero();
        assertThat(jdbc.queryForMap("""
                SELECT account_id, email, phone, full_name, created_channel, link_decision_pending
                FROM customers WHERE id = ?""", counter.id()))
                .containsEntry("account_id", online.accountId())
                .containsEntry("email", online.email()).containsEntry("phone", PHONE)
                .containsEntry("full_name", "Khách quầy " + tag).containsEntry("created_channel", "COUNTER")
                .containsEntry("link_decision_pending", false);
        assertThat(jdbc.queryForMap("SELECT email, status, is_locked FROM accounts WHERE id = ?", online.accountId()))
                .as("tài khoản không đổi gì").containsEntry("email", online.email())
                .containsEntry("status", "ACTIVE").containsEntry("is_locked", false);

        List<Map<String, Object>> audits = audits(counter.id());
        assertThat(audits).as("đúng MỘT dòng audit").hasSize(1);
        Map<String, Object> audit = audits.get(0);
        assertThat(audit).containsEntry("entity_type", "customers").containsEntry("actor_account_id", receptionist)
                .containsEntry("actor_email", email(receptionist));
        Map<String, Object> before = json(audit.get("before_data"));
        Map<String, Object> after = json(audit.get("after_data"));
        assertThat(number(before.get("counterCustomerId"))).isEqualTo(counter.id());
        assertThat(number(before.get("onlineCustomerId"))).isEqualTo(online.profileId());
        assertThat(number(after.get("counterCustomerId"))).isEqualTo(counter.id());
        assertThat(number(after.get("linkedAccountId"))).isEqualTo(online.accountId());
        assertThat(after.get("verificationMethod")).isEqualTo(METHOD);
        assertThat(audit.get("before_data").toString() + audit.get("after_data"))
                .doesNotContain("EMAIL_CODE").doesNotContain(online.email()).doesNotContain(counter.email())
                .doesNotContain(PHONE).doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("otp");
        assertThat(auditCountOn("accounts", online.accountId())).isZero();

        assertThat(outboxCount(online.email())).as("không thông báo cho khách").isZero();
        assertThat(outboxCount(counter.email())).as("không thông báo tới email hồ sơ").isZero();
        assertThat(openSessions(online.accountId())).as("không hủy phiên của khách").isEqualTo(openSessions);
        Map<String, Object> me = map(ok(get("/api/me", customerToken)));
        assertThat(number(me.get("customerId"))).as("GET /api/me thấy hồ sơ quầy").isEqualTo(counter.id());
        assertThat(me).containsEntry("linkDecisionPending", false);
        assertRowNotLocked(online.accountId());

        // Liên kết lần hai: cùng hồ sơ (đã gắn tài khoản) và hồ sơ quầy khác (tài khoản đã liên kết).
        assertRule(link(receptionistToken, counter.id(), online.email()), "BR-TK-19", PROFILE_HAS_ACCOUNT);
        Counter other = counter(newEmail());
        assertRule(link(receptionistToken, other.id(), online.email()), "BR-TK-19", ACCOUNT_LINKED);
        assertUntouchedCounter(other);
        assertThat(audits(counter.id())).hasSize(1);
    }

    // ================================================================ (b) thứ tự kiểm + lỗi

    /** Bean Validation 400 đứng trước {@code @PreAuthorize} 403 (và trước 404): token VET + hồ sơ không có + body sai. */
    @Test
    void invalidBodiesAre400BeforeTheRoleCheckAndBeforeAnyLookup() {
        String vetToken = token(staff("VET", branchA));
        String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60) + ".vn";
        String path = path(987654321L);

        assertThat(validationMessage(vetToken, path, "{}")).contains("accountEmail: Email tài khoản không được để trống")
                .contains("verificationMethod: Phương thức xác minh không được để trống");
        assertThat(validationMessage(vetToken, path, "{\"accountEmail\":\"\",\"verificationMethod\":\"" + METHOD + "\"}"))
                .contains("accountEmail: Email tài khoản không được để trống");
        assertThat(validationMessage(vetToken, path,
                "{\"accountEmail\":\"khong-hop-le\",\"verificationMethod\":\"" + METHOD + "\"}"))
                .contains("accountEmail: Email tài khoản không đúng định dạng");
        assertThat(validationMessage(vetToken, path,
                "{\"accountEmail\":\"" + tooLong + "\",\"verificationMethod\":\"" + METHOD + "\"}"))
                .contains("accountEmail: Email tài khoản tối đa 255 ký tự");
        assertThat(validationMessage(vetToken, path, "{\"accountEmail\":\"a@b.vn\"}"))
                .contains("verificationMethod: Phương thức xác minh không được để trống");
        assertThat(validationMessage(vetToken, path, "{\"accountEmail\":\"a@b.vn\",\"verificationMethod\":null}"))
                .contains("verificationMethod: Phương thức xác minh không được để trống");
    }

    /** Giá trị lạ của enum / JSON hỏng / id không phải số: MALFORMED_REQUEST, không chạm dữ liệu. */
    @Test
    void unknownVerificationMethodAndBrokenInputAreMalformedAndChangeNothing() {
        Online online = online("ACTIVE", false);
        Counter counter = counter(newEmail());

        assertError(raw(path(counter.id()), receptionistToken, "{\"accountEmail\":\"" + online.email()
                + "\",\"verificationMethod\":\"PASSPORT\"}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(path(counter.id()), receptionistToken, "{\"accountEmail\":"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw("/api/customers/abc/link-account", receptionistToken, "{\"accountEmail\":\"" + online.email()
                + "\",\"verificationMethod\":\"" + METHOD + "\"}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");

        assertUntouched(online, counter);
    }

    /** Chỉ RECEPTIONIST (BR-TK-16); body hợp lệ để lỗi 400 không che 403; 403 đứng trước 404 hồ sơ. */
    @ParameterizedTest(name = "{0} → 403")
    @CsvSource({"VET", "CARETAKER", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOtherThanReceptionistAre403BeforeTheProfileLookupAndChangeNothing(String role) {
        Online online = online("ACTIVE", false);
        Counter counter = counter(newEmail());
        String actorToken = token("CUSTOMER".equals(role) ? online.accountId() : staff(role, branchOf(role)));

        assertError(link(actorToken, counter.id(), online.email()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(link(actorToken, 987654321L, online.email()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        assertUntouched(online, counter);
    }

    @Test
    void noTokenIs401AndReceptionistStillOnFirstPasswordIsBrTk17() {
        Online online = online("ACTIVE", false);
        Counter counter = counter(newEmail());
        assertError(link(null, counter.id(), online.email()), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);
        assertThat(((String) link(receptionistToken, counter.id(), online.email()).getBody().get("message")))
                .endsWith("(BR-TK-17)");

        assertUntouched(online, counter);
    }

    @Test
    void missingProfileIs404EvenWhenTheAccountEmailIsUnknown() {
        ResponseEntity<Map<String, Object>> response = link(receptionistToken, 987654321L, newEmail());

        assertError(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(response.getBody().get("message")).isEqualTo("Không tìm thấy hồ sơ khách #987654321");
    }

    /**
     * Hồ sơ đã gắn tài khoản — hồ sơ online của một khách, hoặc hồ sơ quầy đã liên kết: một thông báo chung BR-TK-19, kiểm
     * TRƯỚC khi tìm tài khoản (email lạ vẫn nhận 400 chứ không phải 404).
     */
    @Test
    void profileAlreadyHoldingAnAccountIs400BrTk19BeforeTheAccountLookup() {
        Online target = online("ACTIVE", false);
        Online someone = online("ACTIVE", false);
        Online linkedElsewhere = online("ACTIVE", false);
        jdbc.update("UPDATE customers SET created_channel = 'COUNTER', phone = ?, email = ? WHERE id = ?", PHONE,
                linkedElsewhere.email(), linkedElsewhere.profileId());

        for (long profile : List.of(someone.profileId(), linkedElsewhere.profileId())) {
            assertRule(link(receptionistToken, profile, target.email()), "BR-TK-19", PROFILE_HAS_ACCOUNT);
            assertRule(link(receptionistToken, profile, newEmail()), "BR-TK-19", PROFILE_HAS_ACCOUNT);
        }

        assertThat(exists(target.profileId())).isTrue();
        assertThat(exists(someone.profileId())).isTrue();
        assertThat(accountOfProfile(someone.profileId())).isEqualTo(someone.accountId());
        assertThat(accountOfProfile(linkedElsewhere.profileId())).isEqualTo(linkedElsewhere.accountId());
        assertThat(auditCountOn("customers", someone.profileId()) + auditCountOn("customers",
                linkedElsewhere.profileId())).isZero();
    }

    /**
     * Hồ sơ quầy chưa có email: 400 BR-TK-19 với hướng dẫn nguyên văn, KHÔNG gọi sang customer (không đổi gì) — kiểm trước
     * cả việc tìm tài khoản. Tài khoản hợp lệ (ACTIVE, hồ sơ online liên kết được) để nếu thiếu guard thì customer sẽ ném
     * câu mơ hồ "Hồ sơ tại quầy không còn liên kết được", chứ không phải 404.
     */
    @ParameterizedTest(name = "email hồ sơ = [{0}]")
    @CsvSource(value = {"NULL", "'   '"}, nullValues = "NULL")
    void counterProfileWithoutEmailIs400WithGuidanceAndNothingChanges(String profileEmail) {
        Online online = online("ACTIVE", false);
        long counter = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel)
                VALUES (?, ?, ?, 'COUNTER') RETURNING id""", Long.class, "Khách quầy " + tag, PHONE, profileEmail);

        ResponseEntity<Map<String, Object>> response = link(receptionistToken, counter, online.email());

        assertRule(response, "BR-TK-19", PROFILE_NO_EMAIL);
        assertRule(link(receptionistToken, counter, newEmail()), "BR-TK-19", PROFILE_NO_EMAIL);
        assertThat(exists(online.profileId())).isTrue();
        assertThat(accountOfProfile(counter)).isNull();
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counter))
                .isEqualTo(profileEmail);
        assertThat(audits(counter)).isEmpty();
    }

    @Test
    void unknownAccountEmailIs404WithOneGenericMessage() {
        Counter counter = counter(newEmail());
        String unknown = newEmail();

        ResponseEntity<Map<String, Object>> response = link(receptionistToken, counter.id(), unknown);

        assertAccountNotFound(response, unknown);
        assertUntouchedCounter(counter);
    }

    /**
     * Tài khoản nhân viên: 404 như không có, kể cả khi dữ liệu hỏng — một hồ sơ trỏ nhầm vào nhân viên (CHECK của schema
     * cho phép): chặn bằng kiểm role CUSTOMER, không được lọt xuống {@code checkOnlineProfileLinkable} (400 hoặc 500).
     */
    @Test
    void staffAccountIs404WithAndWithoutAStrayProfileRow() {
        Counter counter = counter(newEmail());
        long plainStaff = staff("VET", branchA);
        long strayStaff = staff("CARETAKER", branchA);
        jdbc.update("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, ?, 'COUNTER')""", strayStaff, "Hồ sơ hỏng " + tag, PHONE);

        assertAccountNotFound(link(receptionistToken, counter.id(), email(plainStaff)), email(plainStaff));
        assertAccountNotFound(link(receptionistToken, counter.id(), email(strayStaff)), email(strayStaff));

        assertUntouchedCounter(counter);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE account_id = ?", Long.class, strayStaff))
                .isEqualTo(1);
        assertThat(auditCountOn("accounts", plainStaff) + auditCountOn("accounts", strayStaff)).isZero();
    }

    /** Dữ liệu hỏng ngược lại (BR-KH-01): tài khoản khách ACTIVE không có hồ sơ → 404, không phải 500. */
    @Test
    void customerAccountWithoutAProfileIs404NotAServerError() {
        Counter counter = counter(newEmail());
        String email = newEmail();
        jdbc.update("INSERT INTO accounts (email, password_hash, role, status) VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE')",
                email);

        assertAccountNotFound(link(receptionistToken, counter.id(), email), email);

        assertUntouchedCounter(counter);
    }

    /**
     * ST02 (BR-TK-08): tài khoản PENDING (có hồ sơ online như lúc đăng ký) KHÔNG được liên kết — nếu hồ sơ quầy bị gắn
     * vào tài khoản PENDING thì job xóa tài khoản quá hạn sẽ xóa luôn hồ sơ quầy (hoặc lặp lỗi vô hạn nếu hồ sơ có dữ
     * liệu). 404 chung, và hồ sơ quầy vẫn chưa gắn.
     */
    @Test
    void pendingAccountIs404AndTheCounterProfileIsNeverAttachedToIt() {
        Online pending = online("PENDING", false);
        Counter counter = counter(newEmail());

        assertAccountNotFound(link(receptionistToken, counter.id(), pending.email()), pending.email());

        assertThat(accountOfProfile(counter.id())).as("hồ sơ quầy không gắn vào tài khoản PENDING").isNull();
        assertThat(exists(pending.profileId())).as("hồ sơ online của tài khoản PENDING còn").isTrue();
        assertThat(accountOfProfile(pending.profileId())).isEqualTo(pending.accountId());
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, pending.accountId()))
                .isEqualTo("PENDING");
        assertUntouchedCounter(counter);
    }

    /** BR-TK-11: tài khoản đang bị khóa → 400, kiểm trước dữ liệu hồ sơ online (khóa + có thú vẫn là BR-TK-11). */
    @Test
    void lockedAccountIs400BrTk11BeforeTheOnlineProfileChecks() {
        Online locked = online("ACTIVE", true);
        Online lockedWithData = online("ACTIVE", true);
        pet(lockedWithData.profileId());
        Counter counter = counter(newEmail());

        assertRule(link(receptionistToken, counter.id(), locked.email()), "BR-TK-11", ACCOUNT_LOCKED);
        assertRule(link(receptionistToken, counter.id(), lockedWithData.email()), "BR-TK-11", ACCOUNT_LOCKED);

        assertUntouched(locked, counter);
        assertThat(exists(lockedWithData.profileId())).isTrue();
        assertThat(accountOfProfile(lockedWithData.profileId())).isEqualTo(lockedWithData.accountId());
    }

    @Test
    void onlineProfileWithDataIs400BrTk19AndNothingIsDeleted() {
        Online online = online("ACTIVE", false);
        address(online.profileId());
        long pet = pet(online.profileId());
        Counter counter = counter(newEmail());

        ResponseEntity<Map<String, Object>> response = link(receptionistToken, counter.id(), online.email());

        assertRule(response, "BR-TK-19", ONLINE_HAS_DATA);
        assertUntouched(online, counter);
        assertThat(addressCount(online.profileId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, pet))
                .isEqualTo(online.profileId());
    }

    /** Tài khoản đã liên kết một hồ sơ tại quầy (hồ sơ của nó là COUNTER): 400, hồ sơ quầy khác không bị đụng. */
    @Test
    void accountAlreadyLinkedToAnotherCounterProfileIs400() {
        Online linked = online("ACTIVE", false);
        jdbc.update("UPDATE customers SET created_channel = 'COUNTER', phone = ? WHERE id = ?", PHONE,
                linked.profileId());
        Counter counter = counter(newEmail());

        assertRule(link(receptionistToken, counter.id(), linked.email()), "BR-TK-19", ACCOUNT_LINKED);

        assertThat(accountOfProfile(linked.profileId())).isEqualTo(linked.accountId());
        assertUntouchedCounter(counter);
    }

    // ================================================================ (c) hướng dẫn không là ngõ cụt

    /**
     * Luồng đầu-cuối thật: lễ tân tạo hồ sơ quầy không email → #27 trả hướng dẫn → sửa hồ sơ khách qua
     * {@code PATCH /api/customers/{id}} (controller customer THẬT) đặt email của tài khoản → #27 thành công.
     */
    @Test
    void guidanceIsNotADeadEndPatchTheEmailThenLink() {
        Online online = online("ACTIVE", false);
        long counter = createCounterViaApi(null);

        assertRule(link(receptionistToken, counter, online.email()), "BR-TK-19", PROFILE_NO_EMAIL);
        assertThat(patchEmail(receptionistToken, counter, online.email()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> response = link(receptionistToken, counter, online.email());

        assertThat(number(map(ok(response)).get("customerId"))).isEqualTo(counter);
        assertThat(jdbc.queryForMap("SELECT account_id, email FROM customers WHERE id = ?", counter))
                .containsEntry("account_id", online.accountId()).containsEntry("email", online.email());
        assertThat(exists(online.profileId())).isFalse();
    }

    /** Email tạm của hồ sơ KHÁC email tài khoản vẫn liên kết được và bị ghi đè bằng email tài khoản. */
    @Test
    void aTemporaryProfileEmailDifferentFromTheAccountEmailStillLinksAndIsOverwritten() {
        Online online = online("ACTIVE", false);
        long counter = createCounterViaApi(null);
        String temporary = "tam-" + UUID.randomUUID() + "@petcare.test";

        assertThat(patchEmail(receptionistToken, counter, temporary).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok(link(receptionistToken, counter, online.email()))).isNotNull();

        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counter))
                .isEqualTo(online.email()).isNotEqualTo(temporary);
    }

    /** {@code customers.email} không unique: hai hồ sơ cùng email tạm cùng tồn tại và cùng liên kết được. */
    @Test
    void twoProfilesMayShareTheSameTemporaryEmailAndBothLink() {
        Online first = online("ACTIVE", false);
        Online second = online("ACTIVE", false);
        long counterA = createCounterViaApi(null);
        long counterB = createCounterViaApi(null);
        String shared = "tam-" + UUID.randomUUID() + "@petcare.test";

        assertThat(patchEmail(receptionistToken, counterA, shared).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchEmail(receptionistToken, counterB, shared).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE email = ?", Long.class, shared))
                .isEqualTo(2);

        assertThat(ok(link(receptionistToken, counterA, first.email()))).isNotNull();
        assertThat(ok(link(receptionistToken, counterB, second.email()))).isNotNull();

        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counterA))
                .isEqualTo(first.email());
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counterB))
                .isEqualTo(second.email());
    }

    // ================================================================ (d) đồng thời

    /** Bấm đúp: cùng tài khoản, cùng hồ sơ — đúng một lần thành công, lần kia 400 BR-TK-19 (hồ sơ đã gắn tài khoản). */
    @Test
    void doubleSubmitOfTheSameLinkSucceedsOnceAndTheOtherIsBrTk19() throws Exception {
        Online online = online("ACTIVE", false);
        Counter counter = counter(newEmail());

        List<ResponseEntity<Map<String, Object>>> responses = whileAccountLocked(online.accountId(),
                List.of(() -> link(receptionistToken, counter.id(), online.email()),
                        () -> link(receptionistToken, counter.id(), online.email())));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        responses.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST)
                .forEach(r -> assertRule(r, "BR-TK-19", PROFILE_HAS_ACCOUNT));
        assertThat(accountOfProfile(counter.id())).isEqualTo(online.accountId());
        assertThat(audits(counter.id())).hasSize(1);
    }

    /** Cùng tài khoản, hai hồ sơ quầy khác nhau: tuần tự dưới khóa {@code accounts}; hồ sơ thứ hai nhận ALREADY_LINKED. */
    @Test
    void oneAccountForTwoCounterProfilesLinksOnlyOne() throws Exception {
        Online online = online("ACTIVE", false);
        Counter first = counter(newEmail());
        Counter second = counter(newEmail());

        List<ResponseEntity<Map<String, Object>>> responses = whileAccountLocked(online.accountId(),
                List.of(() -> link(receptionistToken, first.id(), online.email()),
                        () -> link(receptionistToken, second.id(), online.email())));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        responses.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST)
                .forEach(r -> assertRule(r, "BR-TK-19", ACCOUNT_LINKED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE account_id = ?", Long.class,
                online.accountId())).isEqualTo(1);
        assertThat(audits(first.id()).size() + audits(second.id()).size()).isEqualTo(1);
    }

    /**
     * Cùng hồ sơ quầy, hai tài khoản khác nhau: Y đọc hồ sơ (còn trống) rồi chờ khóa {@code accounts} của Y; X liên kết
     * xong; Y lấy được khóa thì customer từ chối dưới khóa (BR-TK-19) và hồ sơ vẫn thuộc X. Chỉ đúng khi bước ĐỌC hồ
     * sơ chạy ở giao dịch riêng, đã kết thúc trước giao dịch ghi (nếu cùng giao dịch, Hibernate trả lại hồ sơ cũ khi
     * "kiểm lại dưới khóa" và hồ sơ của X bị Y ghi đè).
     */
    @Test
    void counterProfileLinkedByAnotherAccountBetweenReadAndLockIsRejectedUnderLock() throws Exception {
        Online x = online("ACTIVE", false);
        Online y = online("ACTIVE", false);
        address(y.profileId());
        Counter counter = counter(newEmail());

        List<ResponseEntity<Map<String, Object>>> results = new ArrayList<>();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, y.accountId());
            locked.countDown();
            await(release);
        }));
        await(locked);
        CompletableFuture<ResponseEntity<Map<String, Object>>> requestY = CompletableFuture
                .supplyAsync(() -> link(receptionistToken, counter.id(), y.email()));
        waitUntilBlockedOnAccountLock(1);
        results.add(link(receptionistToken, counter.id(), x.email()));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(ok(results.get(0))).isNotNull();
        ResponseEntity<Map<String, Object>> yResponse = requestY.get(20, TimeUnit.SECONDS);
        assertRule(yResponse, "BR-TK-19", NOT_LINKABLE_ANYMORE);
        assertThat(accountOfProfile(counter.id())).as("hồ sơ vẫn thuộc X").isEqualTo(x.accountId());
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counter.id()))
                .isEqualTo(x.email());
        assertThat(exists(y.profileId())).as("hồ sơ online của Y còn").isTrue();
        assertThat(addressCount(y.profileId())).isEqualTo(1);
        assertThat(audits(counter.id())).as("đúng một dòng audit").hasSize(1);
        assertThat(number(json(audits(counter.id()).get(0).get("after_data")).get("linkedAccountId")))
                .isEqualTo(x.accountId());
    }

    /**
     * #27 (lễ tân, hồ sơ C1) và UC07 #13 (khách, hồ sơ C2) cùng chờ khóa {@code accounts} của một tài khoản: tuần tự dưới
     * khóa, không deadlock; đúng một bên thành công, bên kia 400 BR-TK-19.
     */
    @Test
    void counterLinkRacingTheCustomersOwnProfileLinkDoesNotDeadlock() throws Exception {
        Online online = online("ACTIVE", false);
        Counter byReceptionist = counter(newEmail());
        Counter byCustomer = counter(newEmail());
        String customerToken = token(online.accountId());
        assertThat(post("/api/me/link/otp", customerToken, Map.of("customerId", byCustomer.id())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String code = jdbc.queryForObject("""
                SELECT payload->>'ma_otp' FROM notification_outbox WHERE recipient_email = ? ORDER BY id DESC LIMIT 1
                """, String.class, byCustomer.email());

        List<ResponseEntity<Map<String, Object>>> responses = whileAccountLocked(online.accountId(),
                List.of(() -> link(receptionistToken, byReceptionist.id(), online.email()),
                        () -> post("/api/me/link/confirm", customerToken,
                                Map.of("customerId", byCustomer.id(), "code", code))));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        responses.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST).forEach(
                r -> assertThat((String) r.getBody().get("message")).endsWith("(BR-TK-19)"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE account_id = ?", Long.class,
                online.accountId())).isEqualTo(1);
        assertThat(audits(byReceptionist.id()).size() + audits(byCustomer.id()).size()).isEqualTo(1);
        assertThat(exists(online.profileId())).isFalse();
    }

    // ================================================================ (e) rollback

    /**
     * Lỗi ghi sau khi hồ sơ online đã bị xóa và hồ sơ quầy đã được gắn (audit không ghi được; hoặc chính lệnh UPDATE gắn
     * tài khoản vào hồ sơ quầy): rollback toàn bộ — hồ sơ online và sổ địa chỉ còn, hồ sơ quầy chưa gắn, không audit;
     * làm lại sau khi hết lỗi thì thành công.
     */
    @ParameterizedTest(name = "lỗi {0}")
    @CsvSource(delimiter = '|', value = {
            "INSERT audit_logs|INSERT|audit_logs|NEW.action = 'CUSTOMER_PROFILE_LINKED'",
            "UPDATE customers|UPDATE|customers|NEW.account_id IS NOT NULL AND OLD.account_id IS NULL"})
    void writeFailureAfterTheDeleteRollsBackTheWholeLink(String name, String operation, String table,
            String condition) {
        Online online = online("ACTIVE", false);
        address(online.profileId());
        Counter counter = counter(newEmail());

        withTrigger(table, operation, condition, () -> assertError(link(receptionistToken, counter.id(),
                online.email()), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(exists(online.profileId())).isTrue();
        assertThat(accountOfProfile(online.profileId())).isEqualTo(online.accountId());
        assertThat(addressCount(online.profileId())).isEqualTo(1);
        assertUntouchedCounter(counter);
        assertThat(audits(counter.id())).isEmpty();
        assertRowNotLocked(online.accountId());
        assertThat(ok(link(receptionistToken, counter.id(), online.email()))).isNotNull();
        assertThat(accountOfProfile(counter.id())).isEqualTo(online.accountId());
    }

    // ================================================================ helpers — dữ liệu

    private long branch() {
        return jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "CustomerAccountLinkIT " + UUID.randomUUID());
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

    /**
     * Tài khoản khách và hồ sơ online của nó, như lúc đăng ký (không email hồ sơ — {@code createOnlineProfile} không nhận
     * email; PENDING có {@code pending_expires_at}).
     */
    private Online online(String status, boolean locked) {
        String email = newEmail();
        long accountId = jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status, is_locked, pending_expires_at)
                VALUES (?, 'hash', 'CUSTOMER', ?, ?,
                        CASE WHEN CAST(? AS text) = 'PENDING' THEN now() + interval '24 hours' END)
                RETURNING id""", Long.class, email, status, locked, status);
        long profileId = jdbc.queryForObject("""
                INSERT INTO customers (account_id, full_name, phone, created_channel)
                VALUES (?, ?, ?, 'ONLINE') RETURNING id""", Long.class, accountId, "Khách online " + tag, PHONE);
        return new Online(accountId, profileId, email);
    }

    private Counter counter(String email) {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, email, created_channel, created_by)
                VALUES (?, ?, ?, 'COUNTER', ?) RETURNING id""", Long.class, "Khách quầy " + tag, PHONE, email,
                receptionist);
        return new Counter(id, email);
    }

    private void address(long customerId) {
        jdbc.update("""
                INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, province, is_default)
                VALUES (?, 'Nhận', '0900000000', ?, 'Hà Nội', NOT EXISTS (SELECT 1 FROM addresses WHERE customer_id = ?))
                """, customerId, "Số " + SEQ.incrementAndGet() + " Đường A", customerId);
    }

    private long pet(long customerId) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Mực', 'DOG', 'MALE') RETURNING id
                """, Long.class, customerId);
    }

    private static String newEmail() {
        return "customer-link-it-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID() + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "CustomerAccountLinkIT")).accessToken();
    }

    private long openSessions(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE account_id = ? AND revoked_at IS NULL",
                Long.class, accountId);
    }

    private boolean exists(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, customerId) == 1;
    }

    private Long accountOfProfile(long customerId) {
        return jdbc.queryForObject("SELECT account_id FROM customers WHERE id = ?", Long.class, customerId);
    }

    private long addressCount(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, customerId);
    }

    private List<Map<String, Object>> audits(long counterCustomerId) {
        return jdbc.queryForList("""
                SELECT entity_type, actor_account_id, actor_email, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE action = 'CUSTOMER_PROFILE_LINKED' AND entity_id = ? ORDER BY id""",
                counterCustomerId);
    }

    private long auditCountOn(String entityType, long entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = ? AND entity_id = ?",
                Long.class, entityType, entityId);
    }

    private long outboxCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE recipient_email = ?", Long.class,
                email);
    }

    /** Mọi ca lỗi: tài khoản và hồ sơ online còn nguyên, hồ sơ quầy chưa gắn, không audit. */
    private void assertUntouched(Online online, Counter counter) {
        assertThat(exists(online.profileId())).as("hồ sơ online còn").isTrue();
        assertThat(accountOfProfile(online.profileId())).isEqualTo(online.accountId());
        assertUntouchedCounter(counter);
    }

    private void assertUntouchedCounter(Counter counter) {
        assertThat(accountOfProfile(counter.id())).as("hồ sơ quầy chưa gắn tài khoản").isNull();
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, counter.id()))
                .isEqualTo(counter.email());
        assertThat(audits(counter.id())).as("không audit").isEmpty();
    }

    /** Không còn giao dịch nào giữ khóa dòng {@code accounts} (lỗi / thành công đều phải nhả khóa). */
    private void assertRowNotLocked(long accountId) {
        tx.executeWithoutResult(status -> jdbc.queryForObject(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", Long.class, accountId));
    }

    private long createCounterViaApi(String email) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fullName", "Khách quầy " + tag);
        body.put("phone", PHONE);
        body.put("email", email);
        ResponseEntity<Map<String, Object>> response = post("/api/customers", receptionistToken, body);
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return number(map(map(response.getBody().get("data")).get("customer")).get("customerId"));
    }

    private void withTrigger(String table, String operation, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_counter_link_fail_" + suffix;
        String trigger = "trg_it_counter_link_fail_" + suffix;
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

    /**
     * Giữ khóa dòng {@code accounts} ở một transaction khác, bắn mỗi action một request song song, đợi tất cả cùng chờ khóa
     * trên {@code pg_stat_activity}, rồi nhả — các request chạy tuần tự dưới khóa (bước đọc hồ sơ của chúng đã xong).
     */
    private <T> List<T> whileAccountLocked(long accountId, List<Supplier<T>> actions) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
            locked.countDown();
            await(release);
        }));
        await(locked);
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Supplier<T> action : actions) {
            futures.add(CompletableFuture.supplyAsync(action));
        }
        waitUntilBlockedOnAccountLock(actions.size());
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ helpers — HTTP

    private static String path(long customerId) {
        return "/api/customers/" + customerId + "/link-account";
    }

    private ResponseEntity<Map<String, Object>> link(String token, long customerId, String accountEmail) {
        return post(path(customerId), token, Map.of("accountEmail", accountEmail, "verificationMethod", METHOD));
    }

    private ResponseEntity<Map<String, Object>> patchEmail(String token, long customerId, String email) {
        return http.exchange("/api/customers/" + customerId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("email", email), bearer(token)), MAP);
    }

    private String validationMessage(String token, String path, String body) {
        ResponseEntity<Map<String, Object>> response = raw(path, token, body);
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        return (String) response.getBody().get("message");
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

    private static Map<String, Object> json(Object text) {
        try {
            return new ObjectMapper().readValue(text.toString(), JSON_MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /** 400 {@code BUSINESS_RULE_VIOLATION}; message nguyên văn kèm hậu tố {@code " (BR-…)"} do platform nối vào. */
    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId, String message) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).isEqualTo(message + " (" + ruleId + ")");
    }

    /** 404 một thông báo chung cho cả bốn nguyên nhân; không lặp lại email người gọi gõ. */
    private static void assertAccountNotFound(ResponseEntity<Map<String, Object>> response, String typedEmail) {
        assertError(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat((String) response.getBody().get("message")).isEqualTo(ACCOUNT_NOT_FOUND)
                .doesNotContain(typedEmail);
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
