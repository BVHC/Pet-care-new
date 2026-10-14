package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.service.SessionService;

/**
 * T15 phần 2 — {@code GET /api/notification-templates} (#33), {@code PUT …/{code}} (#34), {@code POST
 * …/{code}/restore-default} (#35) qua HTTP trên Postgres 17 thật (UC10; BR-QT-14, 15; docs/adr/0037).
 *
 * <p>Seed V3–V12 có {@code body = default_body} nên so response với seed không bắt được map nhầm cột: mỗi test chèn thêm
 * một dòng {@code IT_TPL_<tag>} có mọi cột khác nhau. Mọi mẫu bị đổi được khôi phục ở {@link #restore()} — DB dùng chung
 * với các IT khác ({@code SchemaMigrationIT.seededNotificationTemplatesAreRestorableAndConsistent}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationTemplateAdminIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> TEMPLATE_FIELDS = Set.of("code", "channel", "subject", "body", "allowedVars",
            "requiredVars", "isDefault");
    private static final String WAITING_ON_TEMPLATE_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from notification_templates%for no key update%'
            """;
    private static final String PATH = "/api/notification-templates";
    private static final String OTP = NotificationTemplateCode.OTP_REGISTER;
    private static final String OTP_SUBJECT = "Mã xác thực đăng ký tài khoản Pet Care";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotificationApi notifications;

    @Autowired
    private InAppNotificationDeliveryService inAppDelivery;

    private TransactionTemplate tx;
    private String tag;
    private String itCode;
    private long branch;
    private long admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        itCode = "IT_TPL_" + tag.toUpperCase();
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'IN_APP', 'Tiêu đề đang dùng {ho_ten}', 'Nội dung đang dùng {ly_do}',
                        'Tiêu đề mặc định {ho_ten}', 'Nội dung mặc định {ly_do}',
                        '["ho_ten", "ly_do", "thoi_diem"]'::jsonb, '["thoi_diem", "ly_do"]'::jsonb)
                """, itCode);
        jdbc.update("UPDATE notification_templates SET body = body || ' {thoi_diem}', "
                + "default_body = default_body || ' {thoi_diem}' WHERE code = ?", itCode);
        branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "NotificationTemplateAdminIT " + tag);
        admin = staff("ADMIN", null);
        adminToken = token(admin);
    }

    @AfterEach
    void restore() {
        jdbc.update("""
                UPDATE notification_templates SET subject = default_subject, body = default_body, updated_by = NULL
                WHERE subject IS DISTINCT FROM default_subject OR body <> default_body OR updated_by IS NOT NULL""");
        jdbc.update("DELETE FROM notification_templates WHERE code = ?", itCode);
    }

    // ================================================================ #33 danh sách

    @Test
    void listReturnsEveryRowInCodeOrderWithContractShapeFromTheDatabase() throws Exception {
        List<Map<String, Object>> rows = list(ok(get(PATH, adminToken)));
        List<Map<String, Object>> expected = jdbc.queryForList("""
                SELECT code, channel, subject, body, allowed_vars::text AS allowed, required_vars::text AS required,
                       (subject IS NOT DISTINCT FROM default_subject AND body = default_body) AS is_default
                FROM notification_templates ORDER BY code""");

        assertThat(rows).hasSize(expected.size()).hasSizeGreaterThanOrEqualTo(11);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> db = expected.get(i);
            assertThat(row).containsOnlyKeys(TEMPLATE_FIELDS)
                    .containsEntry("code", db.get("code"))
                    .containsEntry("channel", db.get("channel"))
                    .containsEntry("subject", db.get("subject"))
                    .containsEntry("body", db.get("body"))
                    .containsEntry("allowedVars", strings((String) db.get("allowed")))
                    .containsEntry("requiredVars", strings((String) db.get("required")))
                    .containsEntry("isDefault", db.get("is_default"));
        }
        // Dòng có mọi cột khác nhau: bản đang dùng, không phải bản mặc định.
        assertThat(rows).filteredOn(row -> itCode.equals(row.get("code"))).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("channel", "IN_APP")
                        .containsEntry("subject", "Tiêu đề đang dùng {ho_ten}")
                        .containsEntry("body", "Nội dung đang dùng {ly_do} {thoi_diem}")
                        .containsEntry("allowedVars", List.of("ho_ten", "ly_do", "thoi_diem"))
                        .containsEntry("requiredVars", List.of("thoi_diem", "ly_do"))
                        .containsEntry("isDefault", false));
        assertThat(rows).extracting(row -> (String) row.get("code")).isSorted();
    }

    // ================================================================ quyền

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER", "CUSTOMER"})
    void nonAdminIs403WithoutAuditOrWrite(String role) {
        long actor = "CUSTOMER".equals(role) ? customerAccount() : staff(role, branch);
        String actorToken = token(actor);
        Map<String, Object> before = row(OTP);

        assertError(get(PATH, actorToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(put(OTP, actorToken, content("Tiêu đề", "Mã {ma_otp}")), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(restoreDefault(OTP, actorToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        assertThat(row(OTP)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class,
                actor)).isZero();
    }

    @Test
    void withoutTokenIs401AndAdminWhoMustChangePasswordIsBlocked() {
        assertError(get(PATH, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(put(OTP, null, content("T", "{ma_otp}")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(restoreDefault(OTP, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long fresh = staff("ADMIN", null);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", fresh);
        String freshToken = token(fresh);
        Map<String, Object> before = row(OTP);

        assertRule(get(PATH, freshToken), "BR-TK-17");
        assertRule(put(OTP, freshToken, content("T", "{ma_otp}")), "BR-TK-17");
        assertRule(restoreDefault(OTP, freshToken), "BR-TK-17");
        assertThat(row(OTP)).isEqualTo(before);
    }

    // ================================================================ #34 thành công

    @Test
    void updateReturnsContractShapeWritesAndAudits() {
        Instant start = Instant.now();
        ResponseEntity<Map<String, Object>> response = put(itCode, adminToken,
                content("Mới {ho_ten}", "Lý do: {ly_do}\nLúc {thoi_diem}"));

        assertThat(response.getBody()).containsEntry("message", "Đã cập nhật mẫu thông báo").containsEntry("code", 200);
        assertThat(map(ok(response))).containsOnlyKeys(TEMPLATE_FIELDS)
                .containsEntry("code", itCode).containsEntry("channel", "IN_APP")
                .containsEntry("subject", "Mới {ho_ten}").containsEntry("body", "Lý do: {ly_do}\nLúc {thoi_diem}")
                .containsEntry("allowedVars", List.of("ho_ten", "ly_do", "thoi_diem"))
                .containsEntry("requiredVars", List.of("thoi_diem", "ly_do"))
                .containsEntry("isDefault", false);
        Map<String, Object> row = row(itCode);
        assertThat(row).containsEntry("subject", "Mới {ho_ten}").containsEntry("body", "Lý do: {ly_do}\nLúc {thoi_diem}")
                .containsEntry("default_subject", "Tiêu đề mặc định {ho_ten}")
                .containsEntry("default_body", "Nội dung mặc định {ly_do} {thoi_diem}")
                .containsEntry("channel", "IN_APP").containsEntry("updated_by", admin);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isAfterOrEqualTo(start.minusSeconds(1));
        assertThat(audits()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("action", "NOTIFICATION_TEMPLATE_UPDATED")
                .containsEntry("entity_type", "notification_templates").containsEntry("entity_id", null)
                .containsEntry("actor_email", email(admin))
                .containsEntry("before_code", itCode)
                .containsEntry("before_subject", "Tiêu đề đang dùng {ho_ten}")
                .containsEntry("before_body", "Nội dung đang dùng {ly_do} {thoi_diem}")
                .containsEntry("after_code", itCode)
                .containsEntry("after_subject", "Mới {ho_ten}")
                .containsEntry("after_body", "Lý do: {ly_do}\nLúc {thoi_diem}"));
        assertThat(list(ok(get(PATH, adminToken)))).filteredOn(r -> itCode.equals(r.get("code"))).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("subject", "Mới {ho_ten}"));
    }

    @Test
    void sameContentIsNotWrittenButStillAuditedWithBeforeEqualAfter() {
        Map<String, Object> before = row(OTP);

        Map<String, Object> data = map(ok(put(OTP, adminToken, content((String) before.get("subject"),
                (String) before.get("body")))));

        assertThat(data).containsEntry("isDefault", true);
        assertThat(row(OTP)).isEqualTo(before);
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit).containsEntry("action", "NOTIFICATION_TEMPLATE_UPDATED");
            assertThat(audit.get("before_json")).isEqualTo(audit.get("after_json"));
        });
    }

    @Test
    void contentIsStoredVerbatimAndDefaultIsComparedCharacterByCharacter() {
        Map<String, Object> seed = row(OTP);
        String body = "\r\n  " + seed.get("body") + "  \r\n";

        assertThat(map(ok(put(OTP, adminToken, content("  " + OTP_SUBJECT + "  ", body)))))
                .containsEntry("subject", "  " + OTP_SUBJECT + "  ").containsEntry("body", body)
                .containsEntry("isDefault", false);
        assertThat(row(OTP)).containsEntry("subject", "  " + OTP_SUBJECT + "  ").containsEntry("body", body);
    }

    @Test
    void onlySubjectAndBodyCanChangeAndScalarsAreCoerced() {
        Map<String, Object> before = row(itCode);

        ok(raw(itCode, adminToken, """
                {"subject": 123, "body": "{ly_do} {thoi_diem}", "channel": "EMAIL", "code": "OTHER",
                 "allowedVars": ["x"], "requiredVars": [], "defaultSubject": "x", "defaultBody": "y",
                 "default_body": "z", "isDefault": true, "updatedBy": 1}"""));

        assertThat(row(itCode)).containsEntry("subject", "123").containsEntry("body", "{ly_do} {thoi_diem}")
                .containsEntry("channel", before.get("channel"))
                .containsEntry("default_subject", before.get("default_subject"))
                .containsEntry("default_body", before.get("default_body"))
                .containsEntry("allowed_vars", before.get("allowed_vars"))
                .containsEntry("required_vars", before.get("required_vars"))
                .containsEntry("updated_by", admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_templates WHERE code = 'OTHER'",
                Integer.class)).isZero();
    }

    /** G7: worker render lúc gửi — dòng IN_APP đã xếp hàng trước khi sửa vẫn được giao theo nội dung mới. */
    @Test
    void editAppliesToAnInAppNotificationQueuedBeforeIt() {
        tx.executeWithoutResult(status -> notifications.enqueue(new NotificationRequest(
                NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP, Channel.IN_APP, admin, null,
                Map.of("email_tai_khoan", "khoa@petcare.test", "ly_do", "Spam " + tag, "thoi_diem", "08:00 13/10/2026",
                        "viec_do_dang", "Không có"), null)));
        long outboxId = jdbc.queryForObject("SELECT max(id) FROM notification_outbox WHERE recipient_account_id = ?",
                Long.class, admin);

        ok(put(NotificationTemplateCode.ACCOUNT_LOCKED_FOLLOW_UP_APP, adminToken,
                content("Đã khóa {email_tai_khoan}", "Lý do mới: {ly_do} lúc {thoi_diem}\n{viec_do_dang}")));
        deliverUntilSent(outboxId);

        assertThat(jdbc.queryForMap("SELECT type, title, body FROM notifications WHERE account_id = ?", admin))
                .containsEntry("type", "ACCOUNT_LOCKED_FOLLOW_UP")
                .containsEntry("title", "Đã khóa khoa@petcare.test")
                .containsEntry("body", "Lý do mới: Spam " + tag + " lúc 08:00 13/10/2026\nKhông có");
    }

    /** G6: bản mặc định của mọi mẫu seed qua được luật biến mới, nên khôi phục luôn ra mẫu hợp lệ. */
    @Test
    void everySeededDefaultPassesValidation() {
        List<Map<String, Object>> seeds = jdbc.queryForList("""
                SELECT code, default_subject, default_body FROM notification_templates
                WHERE code NOT LIKE 'IT\\_%' ORDER BY code""");

        assertThat(seeds).hasSizeGreaterThanOrEqualTo(10);
        for (Map<String, Object> seed : seeds) {
            assertThat(map(ok(put((String) seed.get("code"), adminToken,
                    content((String) seed.get("default_subject"), (String) seed.get("default_body"))))))
                    .as((String) seed.get("code")).containsEntry("isDefault", true);
        }
    }

    // ================================================================ #34 BR-QT-14

    @Test
    void unknownVariablesAreAllNamedAndNothingChanges() {
        StringBuilder body = new StringBuilder("Mã {ma_otp}");
        for (int i = 1; i <= 12; i++) {
            body.append(" {bien_").append(i).append('}');
        }
        Map<String, Object> before = row(OTP);

        ResponseEntity<Map<String, Object>> response = put(OTP, adminToken, content("Tiêu đề {abc}", body.toString()));

        assertRule(response, "BR-QT-14");
        String message = (String) response.getBody().get("message");
        assertThat(message).startsWith("Tiêu đề dùng biến không có: {abc}; Nội dung dùng biến không có: {bien_1}")
                .contains("Biến dùng được: {ten_khach}, {ma_otp}, {thoi_han_phut}");
        for (int i = 1; i <= 12; i++) {
            assertThat(message).contains("{bien_" + i + "}");
        }
        assertUnchanged(OTP, before);
    }

    @Test
    void missingRequiredVariableIsNamed() {
        Map<String, Object> before = row(OTP);

        ResponseEntity<Map<String, Object>> response = put(OTP, adminToken, content("Mã xác thực",
                "Chào {ten_khach}, mã còn {thoi_han_phut} phút"));

        assertRule(response, "BR-QT-14");
        assertThat(response.getBody()).containsEntry("message", "Mẫu thiếu biến bắt buộc: {ma_otp} (BR-QT-14)");
        assertUnchanged(OTP, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mã {ma_otp", "Mã ma_otp}", "Mã {{ma_otp}}", "{ma_otp} {", "{ma_otp} }"})
    void strayBraceIsRejected(String body) {
        Map<String, Object> before = row(OTP);

        ResponseEntity<Map<String, Object>> response = put(OTP, adminToken, content("Tiêu đề", body));

        assertRule(response, "BR-QT-14");
        assertThat((String) response.getBody().get("message")).contains("Nội dung có dấu { hoặc } lẻ");
        assertUnchanged(OTP, before);
    }

    @Test
    void variablesAreCheckedPerTemplate() {
        // {ma_otp} hợp lệ ở OTP_REGISTER nhưng không có trong mẫu IN_APP này.
        ResponseEntity<Map<String, Object>> response = put(itCode, adminToken,
                content("Tiêu đề", "{ly_do} {thoi_diem} {ma_otp}"));

        assertRule(response, "BR-QT-14");
        assertThat((String) response.getBody().get("message")).contains("{ma_otp}")
                .contains("Biến dùng được: {ho_ten}, {ly_do}, {thoi_diem}");
    }

    // ================================================================ #34, #35 hình thức / tồn tại / method

    @Test
    void formErrorsAndMalformedBodies() {
        Map<String, Object> before = row(OTP);

        assertValidation(raw(OTP, adminToken, "{\"body\": \"{ma_otp}\"}"), "subject: Vui lòng nhập tiêu đề");
        assertValidation(put(OTP, adminToken, content("   ", "{ma_otp}")), "subject: Vui lòng nhập tiêu đề");
        assertValidation(put(OTP, adminToken, content("x".repeat(256), "{ma_otp}")),
                "subject: Tiêu đề tối đa 255 ký tự");
        assertValidation(raw(OTP, adminToken, "{\"subject\": \"T\"}"), "body: Vui lòng nhập nội dung");
        assertValidation(put(OTP, adminToken, content("T", " \n ")), "body: Vui lòng nhập nội dung");
        assertError(raw(OTP, adminToken, ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw(OTP, adminToken, "{\"subject\": \"T\", \"body\": [\"{ma_otp}\"]}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(OTP, adminToken, "{\"subject\": \"T\", \"body\": {}}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw(OTP, adminToken, "{\"subject\": \"T\""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");

        assertUnchanged(OTP, before);
        // 255 ký tự là biên hợp lệ.
        ok(put(OTP, adminToken, content("ê".repeat(255), "{ma_otp}")));
    }

    @Test
    void unknownCodeIs404() {
        ResponseEntity<Map<String, Object>> update = put("NO_SUCH_TEMPLATE", adminToken, content("T", "B"));
        ResponseEntity<Map<String, Object>> restore = restoreDefault("NO_SUCH_TEMPLATE", adminToken);

        assertError(update, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(update.getBody()).containsEntry("message", "Không tìm thấy mẫu thông báo #NO_SUCH_TEMPLATE");
        assertError(restore, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(restore.getBody()).containsEntry("message", "Không tìm thấy mẫu thông báo #NO_SUCH_TEMPLATE");
        assertThat(audits()).isEmpty();
    }

    /** BR-QT-14: không thêm, không xóa mẫu. */
    @Test
    void addingOrDeletingTemplatesIsNotPossible() {
        int count = jdbc.queryForObject("SELECT count(*) FROM notification_templates", Integer.class);

        assertError(http.exchange(PATH, HttpMethod.POST, json("{\"code\":\"NEW\",\"body\":\"x\"}", adminToken), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(http.exchange(PATH + "/" + OTP, HttpMethod.DELETE, new HttpEntity<>(bearer(adminToken)), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(http.exchange(PATH + "/" + OTP + "/restore-default", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)), MAP), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_templates", Integer.class)).isEqualTo(count);
    }

    // ================================================================ #35

    @Test
    void restoreCopiesDefaultsAndAudits() {
        ok(put(itCode, adminToken, content("Sửa {ho_ten}", "Sửa {ly_do} {thoi_diem}")));

        ResponseEntity<Map<String, Object>> response = restoreDefault(itCode, adminToken);

        assertThat(response.getBody()).containsEntry("message", "Đã khôi phục mẫu mặc định");
        assertThat(map(ok(response))).containsOnlyKeys(TEMPLATE_FIELDS)
                .containsEntry("subject", "Tiêu đề mặc định {ho_ten}")
                .containsEntry("body", "Nội dung mặc định {ly_do} {thoi_diem}")
                .containsEntry("isDefault", true);
        assertThat(row(itCode)).containsEntry("subject", "Tiêu đề mặc định {ho_ten}")
                .containsEntry("body", "Nội dung mặc định {ly_do} {thoi_diem}").containsEntry("updated_by", admin);
        List<Map<String, Object>> audits = audits();
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1)).containsEntry("action", "NOTIFICATION_TEMPLATE_RESTORED")
                .containsEntry("before_subject", "Sửa {ho_ten}")
                .containsEntry("after_subject", "Tiêu đề mặc định {ho_ten}")
                .containsEntry("after_body", "Nội dung mặc định {ly_do} {thoi_diem}");
    }

    @Test
    void restoreOfADefaultTemplateIsNotWrittenButStillAudited() {
        Map<String, Object> before = row(OTP);

        assertThat(map(ok(restoreDefault(OTP, adminToken)))).containsEntry("isDefault", true);

        assertThat(row(OTP)).isEqualTo(before);
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit).containsEntry("action", "NOTIFICATION_TEMPLATE_RESTORED");
            assertThat(audit.get("before_json")).isEqualTo(audit.get("after_json"));
        });
    }

    // ================================================================ commit / rollback

    /** G10: audit lỗi → rollback cả UPDATE đã flush (nhánh sửa) và không còn gì (nhánh trùng). */
    @Test
    void failingAuditRollsBackTheExecutedUpdate() {
        Map<String, Object> before = row(OTP);

        withFailingTrigger("audit_logs", "NEW.action LIKE 'NOTIFICATION_TEMPLATE_%'", false, () -> {
            assertError(put(OTP, adminToken, content("Mới", "Mã {ma_otp}")), HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR");
            assertError(put(OTP, adminToken, content((String) before.get("subject"), (String) before.get("body"))),
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
            assertError(restoreDefault(OTP, adminToken), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        });

        assertUnchanged(OTP, before);
    }

    /** G10: UPDATE lỗi → không có audit, không đổi gì. */
    @Test
    void failingUpdateLeavesNoAudit() {
        Map<String, Object> before = row(OTP);
        String function = "it_tpl_upd_fail_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.code = '%s' THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, OTP));
        jdbc.execute("CREATE TRIGGER trg_%s BEFORE UPDATE ON notification_templates FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(function, function));
        try {
            assertError(put(OTP, adminToken, content("Mới", "Mã {ma_otp}")), HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR");
        } finally {
            jdbc.execute("DROP TRIGGER trg_" + function + " ON notification_templates");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertUnchanged(OTP, before);
    }

    /** G9: 200 ⇔ đã commit. Trigger hoãn tới COMMIT nổ → 500, mẫu và audit như chưa có gì. */
    @Test
    void commitFailureReturns500AndKeepsOldContent() {
        Map<String, Object> before = row(OTP);

        withFailingTrigger("audit_logs", "NEW.action LIKE 'NOTIFICATION_TEMPLATE_%'", true, () ->
                assertError(put(OTP, adminToken, content("Mới", "Mã {ma_otp}")), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertUnchanged(OTP, before);
    }

    // ================================================================ đồng thời

    /** G5: hai PUT cùng mẫu nối tiếp nhau dưới khóa dòng; {@code before} của lần sau là {@code after} của lần trước. */
    @Test
    void concurrentEditsAreSerialisedAndAuditsChain() throws Exception {
        List<ResponseEntity<Map<String, Object>>> responses = whileTemplateLocked(OTP,
                () -> put(OTP, adminToken, content("Lần A", "A {ma_otp}")),
                () -> put(OTP, adminToken, content("Lần B", "B {ma_otp}")));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        List<Map<String, Object>> audits = audits();
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0)).containsEntry("before_subject", OTP_SUBJECT);
        assertThat(audits.get(1).get("before_json")).isEqualTo(audits.get(0).get("after_json"));
        Map<String, Object> last = row(OTP);
        assertThat(audits.get(1)).containsEntry("after_subject", last.get("subject"))
                .containsEntry("after_body", last.get("body"));
    }

    /** G11: lỗi nghiệp vụ / 404 kết thúc transaction và nhả khóa ngay. */
    @Test
    void businessErrorsReleaseTheRowLock() {
        assertRule(put(OTP, adminToken, content("T", "{abc}")), "BR-QT-14");
        assertNotLocked(OTP);
        assertError(put("NO_SUCH_TEMPLATE", adminToken, content("T", "B")), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertNotLocked(OTP);
    }

    // ================================================================ helpers — trạng thái

    private Map<String, Object> row(String code) {
        return jdbc.queryForMap("""
                SELECT code, channel, subject, body, default_subject, default_body, allowed_vars::text AS allowed_vars,
                       required_vars::text AS required_vars, updated_by, updated_at
                FROM notification_templates WHERE code = ?""", code);
    }

    /** Lỗi không để lại gì: dòng như cũ (kể cả {@code updated_at}, {@code updated_by}), không có audit. */
    private void assertUnchanged(String code, Map<String, Object> before) {
        assertThat(row(code)).isEqualTo(before);
        assertThat(audits()).isEmpty();
        assertNotLocked(code);
    }

    private List<Map<String, Object>> audits() {
        return jdbc.queryForList("""
                SELECT action, entity_type, entity_id, actor_email,
                       before_data ->> 'code' AS before_code, before_data ->> 'subject' AS before_subject,
                       before_data ->> 'body' AS before_body, after_data ->> 'code' AS after_code,
                       after_data ->> 'subject' AS after_subject, after_data ->> 'body' AS after_body,
                       before_data::text AS before_json, after_data::text AS after_json
                FROM audit_logs WHERE actor_account_id = ? AND action LIKE 'NOTIFICATION_TEMPLATE_%' ORDER BY id""",
                admin);
    }

    private void assertNotLocked(String code) {
        tx.executeWithoutResult(status ->
                jdbc.queryForList("SELECT code FROM notification_templates WHERE code = ? FOR UPDATE NOWAIT", code));
    }

    private void deliverUntilSent(long outboxId) {
        for (int i = 0; i < 100; i++) {
            String status = jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class,
                    outboxId);
            if ("SENT".equals(status)) {
                return;
            }
            assertThat(status).isEqualTo("PENDING");
            if (inAppDelivery.deliverNext(Instant.now().plusSeconds(1)) == DispatchResult.NONE) {
                break;
            }
        }
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, outboxId))
                .isEqualTo("SENT");
    }

    // ================================================================ helpers — đồng thời, trigger

    @SafeVarargs
    private List<ResponseEntity<Map<String, Object>>> whileTemplateLocked(String code,
            Callable<ResponseEntity<Map<String, Object>>>... requests) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.length + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT code FROM notification_templates WHERE code = ? FOR UPDATE", code);
                locked.countDown();
                try {
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
            for (int i = 0; i < requests.length; i++) {
                futures.add(pool.submit(requests[i]));
                waitForBlocked(i + 1);
            }
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void waitForBlocked(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(WAITING_ON_TEMPLATE_LOCK, Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Không thấy " + expected + " request chờ khóa notification_templates");
    }

    /**
     * Trigger tạm làm INSERT vào {@code table} lỗi khi {@code condition} đúng. {@code atCommit}: constraint trigger
     * {@code DEFERRABLE INITIALLY DEFERRED} — chỉ nổ lúc COMMIT, sau khi method service đã trả về.
     */
    private void withFailingTrigger(String table, String condition, boolean atCommit, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_tpl_fail_" + suffix;
        String trigger = "trg_it_tpl_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        if (atCommit) {
            jdbc.execute(("CREATE CONSTRAINT TRIGGER %s AFTER INSERT ON %s DEFERRABLE INITIALLY DEFERRED "
                    + "FOR EACH ROW EXECUTE FUNCTION %s()").formatted(trigger, table, function));
        } else {
            jdbc.execute("CREATE TRIGGER %s BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                    .formatted(trigger, table, function));
        }
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    // ================================================================ helpers — dữ liệu, HTTP

    private long staff(String role, Long branchId) {
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, newEmail(), role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag + " " + id, branchId);
        return id;
    }

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
    }

    private String newEmail() {
        return "template-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "NotificationTemplateAdminIT"))
                .accessToken();
    }

    private static Map<String, Object> content(String subject, String body) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subject", subject);
        content.put("body", body);
        return content;
    }

    private static List<String> strings(String jsonArray) {
        try {
            return JSON.readValue(jsonArray, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> put(String code, String token, Object body) {
        return http.exchange(PATH + "/" + code, HttpMethod.PUT, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String code, String token, String body) {
        return http.exchange(PATH + "/" + code, HttpMethod.PUT, json(body, token), MAP);
    }

    private ResponseEntity<Map<String, Object>> restoreDefault(String code, String token) {
        return http.exchange(PATH + "/" + code + "/restore-default", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), MAP);
    }

    private static HttpEntity<String> json(String body, String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
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

    private static void assertRule(ResponseEntity<Map<String, Object>> response, String ruleId) {
        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION");
        assertThat((String) response.getBody().get("message")).endsWith("(" + ruleId + ")");
    }

    private static void assertValidation(ResponseEntity<Map<String, Object>> response, String message) {
        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(response.getBody()).containsEntry("message", message);
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
