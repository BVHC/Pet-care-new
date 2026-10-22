package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.repository.SystemConfigRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;

/**
 * T15 phần 1 — {@code GET /api/system-configs} (#31), {@code PUT /api/system-configs/{key}} (#32) qua HTTP trên
 * Postgres 17 thật (UC10; BR-QT-13, 15; docs/adr/0036). {@link SystemConfigRepository} là spy gọi bản thật, chỉ chen
 * vào khi một test bật cờ: barrier ở {@code flush()} (làm lộ write skew nếu ai đó bỏ khóa khóa kia của cặp) và một lần
 * {@code findAll()} lỗi (reload hỏng sau commit). Mọi giá trị bị đổi được khôi phục trong {@link #restore()}: context và
 * DB dùng chung với các IT khác ({@code SystemConfigIT.seedMatchesCatalogExactly}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SystemConfigAdminIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final Set<String> CONFIG_FIELDS = Set.of("key", "value", "valueType", "minValue", "maxValue",
            "unit", "description");
    private static final String WAITING_ON_CONFIG_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from system_configs%for no key update%'
            """;
    private static final String PATH = "/api/system-configs";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SystemConfigService configs;

    @MockitoSpyBean
    private SystemConfigRepository repository;

    private final AtomicBoolean failNextFindAll = new AtomicBoolean();
    private volatile CyclicBarrier flushBarrier;
    private Map<String, String> original;
    private TransactionTemplate tx;
    private String tag;
    private long branch;
    private long admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tag = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        original = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM system_configs ORDER BY key",
                rs -> {
                    original.put(rs.getString("key"), rs.getString("value"));
                });
        branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "SystemConfigAdminIT " + tag);
        admin = staff("ADMIN", null);
        adminToken = token(admin);

        Answer<?> real = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            CyclicBarrier barrier = flushBarrier;
            if (barrier != null) {
                try {
                    barrier.await(3, TimeUnit.SECONDS);
                } catch (TimeoutException | BrokenBarrierException expected) {
                    // Đúng thiết kế: request kia đang chờ khóa dòng nên không bao giờ tới đây cùng lúc.
                }
            }
            return real.answer(invocation);
        }).when(repository).flush();
        doAnswer(invocation -> {
            if (failNextFindAll.getAndSet(false)) {
                throw new DataAccessResourceFailureException("IT: DB tạm mất kết nối");
            }
            return real.answer(invocation);
        }).when(repository).findAll();
    }

    @AfterEach
    void restore() {
        flushBarrier = null;
        failNextFindAll.set(false);
        original.forEach((key, value) ->
                jdbc.update("UPDATE system_configs SET value = ?, updated_by = NULL WHERE key = ?", value, key));
        configs.reload();
    }

    // ================================================================ #31 danh sách

    @Test
    void listReturnsEveryRowInKeyOrderWithContractShapeFromTheDatabase() {
        List<Map<String, Object>> rows = list(ok(get(PATH, adminToken)));
        List<Map<String, Object>> expected = jdbc.queryForList("""
                SELECT key, value, value_type, min_value, max_value, unit, description
                FROM system_configs ORDER BY key""");

        assertThat(rows).hasSize(ConfigKey.values().length).hasSize(expected.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> db = expected.get(i);
            assertThat(row).containsOnlyKeys(CONFIG_FIELDS)
                    .containsEntry("key", db.get("key"))
                    .containsEntry("value", db.get("value"))
                    .containsEntry("valueType", db.get("value_type"))
                    .containsEntry("minValue", db.get("min_value"))
                    .containsEntry("maxValue", db.get("max_value"))
                    .containsEntry("unit", db.get("unit"))
                    .containsEntry("description", db.get("description"));
        }
        assertThat(rows).extracting(row -> (String) row.get("key")).isSorted();
    }

    // ================================================================ quyền

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER", "CUSTOMER"})
    void nonAdminIs403WithoutAuditOrWrite(String role) {
        long actor = "CUSTOMER".equals(role) ? customerAccount() : staff(role, branch);
        String actorToken = token(actor);
        Snapshot before = snapshot("otp.ttl_minutes");

        assertError(get(PATH, actorToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(put("otp.ttl_minutes", actorToken, Map.of("value", "10")), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");

        assertUnchanged(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_account_id = ?", Long.class,
                actor)).isZero();
    }

    @Test
    void withoutTokenIs401AndAdminWhoMustChangePasswordIsBlocked() {
        assertError(get(PATH, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(put("otp.ttl_minutes", null, Map.of("value", "10")), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long fresh = staff("ADMIN", null);
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", fresh);
        String freshToken = token(fresh);
        Snapshot before = snapshot("otp.ttl_minutes");

        assertRule(get(PATH, freshToken), "BR-TK-17");
        assertRule(put("otp.ttl_minutes", freshToken, Map.of("value", "10")), "BR-TK-17");
        assertUnchanged(before);
    }

    // ================================================================ #32 thành công

    @Test
    void updateReturnsContractShapeWritesAuditsAndCacheBeforeTheResponse() {
        Instant start = Instant.now();
        ResponseEntity<Map<String, Object>> response = put("otp.ttl_minutes", adminToken, Map.of("value", "10"));

        assertThat(response.getBody()).containsEntry("message", "Đã cập nhật tham số").containsEntry("code", 200);
        assertThat(map(ok(response))).containsOnlyKeys(CONFIG_FIELDS)
                .containsEntry("key", "otp.ttl_minutes").containsEntry("value", "10")
                .containsEntry("valueType", "INT").containsEntry("minValue", "1").containsEntry("maxValue", "15")
                .containsEntry("unit", "phút")
                .containsEntry("description", "Thời gian hiệu lực của mã OTP (BR-TK-05)");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT value, updated_by, updated_at FROM system_configs WHERE key = 'otp.ttl_minutes'");
        assertThat(row).containsEntry("value", "10").containsEntry("updated_by", admin);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isAfterOrEqualTo(start.minusSeconds(1));
        // Cache đã đổi ngay khi response về (reload trước khi controller trả).
        assertThat(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(10);
        assertThat(audits()).singleElement().satisfies(audit -> assertThat(audit)
                .containsEntry("entity_type", "system_configs").containsEntry("entity_id", null)
                .containsEntry("actor_email", email(admin))
                .containsEntry("before_data", "{\"key\": \"otp.ttl_minutes\", \"value\": \"5\"}")
                .containsEntry("after_data", "{\"key\": \"otp.ttl_minutes\", \"value\": \"10\"}"));
        // Danh sách cũng thấy giá trị mới.
        assertThat(list(ok(get(PATH, adminToken)))).filteredOn(r -> "otp.ttl_minutes".equals(r.get("key")))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("value", "10"));
    }

    /** BR-QT-13 "giao dịch tạo sau": quên mật khẩu ngay sau PUT chốt TTL mới vào {@code otp_tokens.expires_at}. */
    @Test
    void newValueAppliesToTheNextTransaction() {
        long vet = staff("VET", branch);
        ok(put("otp.ttl_minutes", adminToken, Map.of("value", "10")));

        ResponseEntity<Map<String, Object>> forgot = http.exchange("/api/auth/password/forgot", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email(vet)), bearer(null)), MAP);

        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Double seconds = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM expires_at - created_at)::float8 FROM otp_tokens
                WHERE account_id = ? ORDER BY id DESC LIMIT 1""", Double.class, vet);
        assertThat(seconds).isBetween(599.0, 600.0);
    }

    @Test
    void valueIsStoredCanonicalAndJsonScalarsAreCoerced() {
        assertThat(map(ok(put("otp.ttl_minutes", adminToken, Map.of("value", "07"))))).containsEntry("value", "7");
        assertThat(map(ok(raw("otp.ttl_minutes", adminToken, "{\"value\": 8, \"key\": \"session.ttl_hours\"}"))))
                .containsEntry("key", "otp.ttl_minutes").containsEntry("value", "8");
        assertThat(jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'session.ttl_hours'",
                String.class)).isEqualTo(original.get("session.ttl_hours"));
        assertThat(map(ok(put("boarding.checkout_time", adminToken, Map.of("value", " 13:30 ")))))
                .containsEntry("value", "13:30");
        assertThat(configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)).hasToString("13:30");
    }

    @Test
    void sameValueIsOkWithoutWriteOrAudit() {
        Snapshot before = snapshot("otp.ttl_minutes");

        assertThat(map(ok(put("otp.ttl_minutes", adminToken, Map.of("value", "005"))))).containsEntry("value", "5");

        assertUnchanged(before);
        assertThat(audits()).isEmpty();
    }

    // ================================================================ #32 BR-QT-13 — đủ 52 khóa

    /**
     * Mỗi khóa: dưới min, trên max, không parse được → 400 BR-QT-13 với khoảng cho phép thực tế; bằng min và bằng max →
     * 200 (trừ biên bị ràng buộc chéo chặn). Khoảng kỳ vọng tính độc lập từ seed V2 và bảng cặp của ADR-0036.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ConfigKey.class)
    void boundariesOfEveryKey(ConfigKey key) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT min_value, max_value, unit FROM system_configs WHERE key = ?", key.key());
        String min = (String) row.get("min_value");
        String max = (String) row.get("max_value");
        String unit = (String) row.get("unit");
        Expected expected = expected(key, min, max, unit);

        for (String invalid : expected.invalid()) {
            Snapshot before = snapshot(key.key());
            ResponseEntity<Map<String, Object>> response = put(key.key(), adminToken, Map.of("value", invalid));
            assertRule(response, "BR-QT-13");
            assertThat(response.getBody()).containsEntry("message", expected.message());
            assertUnchanged(before);
        }
        for (String valid : expected.valid()) {
            assertThat(map(ok(put(key.key(), adminToken, Map.of("value", valid))))).containsEntry("value", valid);
            assertThat(cached(key)).isEqualTo(valid);
            configs.reload(); // G2: giá trị API đã nhận luôn nạp lại được
        }
    }

    @Test
    void crossKeyIsCheckedAgainstTheCurrentPartnerValue() {
        assertThat(put("visit.late_priority_minutes", adminToken, Map.of("value", "30")).getBody())
                .containsEntry("message", "Giá trị của visit.late_priority_minutes phải là số nguyên từ 0 đến 29 "
                        + "(phút): phải nhỏ hơn appointment.no_show_after_minutes đang là 30 (BR-QT-13)");

        ok(put("appointment.no_show_after_minutes", adminToken, Map.of("value", "40")));
        ok(put("visit.late_priority_minutes", adminToken, Map.of("value", "30")));

        assertThat(put("appointment.no_show_after_minutes", adminToken, Map.of("value", "30")).getBody())
                .containsEntry("message", "Giá trị của appointment.no_show_after_minutes phải là số nguyên từ 31 đến "
                        + "120 (phút): phải lớn hơn visit.late_priority_minutes đang là 30 (BR-QT-13)");
        assertNotLocked("appointment.no_show_after_minutes", "visit.late_priority_minutes");

        ok(put("boarding.max_nights", adminToken, Map.of("value", "5")));
        assertThat(put("boarding.min_nights", adminToken, Map.of("value", "6")).getBody())
                .containsEntry("message", "Giá trị của boarding.min_nights phải là số nguyên từ 1 đến 5 (đêm): không "
                        + "được lớn hơn boarding.max_nights đang là 5 (BR-QT-13)");
        ok(put("boarding.min_nights", adminToken, Map.of("value", "5")));
    }

    /**
     * BR-LH-12 (docs/adr/0056): nhắc lịch phải trước mốc hủy muộn — {@code late_cancel_hours < reminder_hours_before},
     * kiểm theo cả hai chiều sửa, biên bằng nhau bị từ chối, lỗi không để lại gì.
     */
    @Test
    void reminderMustStayBeforeLateCancelMark() {
        Snapshot reminder = snapshot("appointment.reminder_hours_before");
        assertThat(put("appointment.reminder_hours_before", adminToken, Map.of("value", "12")).getBody())
                .containsEntry("message", "Giá trị của appointment.reminder_hours_before phải là số nguyên từ 13 đến "
                        + "72 (giờ): phải lớn hơn appointment.late_cancel_hours đang là 12 (BR-QT-13)");
        assertUnchanged(reminder);

        Snapshot lateCancel = snapshot("appointment.late_cancel_hours");
        assertThat(put("appointment.late_cancel_hours", adminToken, Map.of("value", "24")).getBody())
                .containsEntry("message", "Giá trị của appointment.late_cancel_hours phải là số nguyên từ 0 đến 23 "
                        + "(giờ): phải nhỏ hơn appointment.reminder_hours_before đang là 24 (BR-QT-13)");
        assertUnchanged(lateCancel);

        ok(put("appointment.reminder_hours_before", adminToken, Map.of("value", "48")));
        ok(put("appointment.late_cancel_hours", adminToken, Map.of("value", "30")));
        assertThat(put("appointment.reminder_hours_before", adminToken, Map.of("value", "30")).getBody())
                .containsEntry("message", "Giá trị của appointment.reminder_hours_before phải là số nguyên từ 31 đến "
                        + "72 (giờ): phải lớn hơn appointment.late_cancel_hours đang là 30 (BR-QT-13)");
        assertNotLocked("appointment.late_cancel_hours", "appointment.reminder_hours_before");
        assertThat(configs.getInt(ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS))
                .isLessThan(configs.getInt(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE));
    }

    /** Như {@link #pairEditedConcurrentlyNeverBreaksTheConstraint} cho cặp nhắc lịch / hủy muộn (docs/adr/0056). */
    @Test
    void reminderPairEditedConcurrentlyNeverBreaksTheConstraint() throws Exception {
        flushBarrier = new CyclicBarrier(2);

        List<ResponseEntity<Map<String, Object>>> responses = whileConfigsLocked(
                List.of("appointment.late_cancel_hours", "appointment.reminder_hours_before"),
                () -> put("appointment.late_cancel_hours", adminToken, Map.of("value", "20")),
                () -> put("appointment.reminder_hours_before", adminToken, Map.of("value", "18")));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        int lateCancel = Integer.parseInt(jdbc.queryForObject(
                "SELECT value FROM system_configs WHERE key = 'appointment.late_cancel_hours'", String.class));
        int reminder = Integer.parseInt(jdbc.queryForObject(
                "SELECT value FROM system_configs WHERE key = 'appointment.reminder_hours_before'", String.class));
        assertThat(lateCancel).isLessThan(reminder);
        assertThat(audits()).hasSize(1);
    }

    // ================================================================ #32 lỗi hình thức / tồn tại / method

    @Test
    void unknownKeyIs404() {
        ResponseEntity<Map<String, Object>> response = put("otp.unknown", adminToken, Map.of("value", "5"));

        assertError(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(response.getBody()).containsEntry("message", "Không tìm thấy tham số #otp.unknown");
        assertThat(audits()).isEmpty();
    }

    @Test
    void malformedBodiesAndFormErrors() {
        Snapshot before = snapshot("otp.ttl_minutes");

        assertError(raw("otp.ttl_minutes", adminToken, ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw("otp.ttl_minutes", adminToken, "{\"value\": {}}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(raw("otp.ttl_minutes", adminToken, "[\"5\"]"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(raw("otp.ttl_minutes", adminToken, "{\"value\": \"5\""), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertValidation(raw("otp.ttl_minutes", adminToken, "{}"), "value: Vui lòng nhập giá trị");
        assertValidation(put("otp.ttl_minutes", adminToken, Map.of("value", "   ")), "value: Vui lòng nhập giá trị");
        assertValidation(put("otp.ttl_minutes", adminToken, Map.of("value", "1".repeat(256))),
                "value: Giá trị tối đa 255 ký tự");
        // Boolean JSON được ép thành "true" → sai kiểu INT → BR-QT-13 (diễn giải D1).
        assertRule(raw("otp.ttl_minutes", adminToken, "{\"value\": true}"), "BR-QT-13");
        assertRule(raw("otp.ttl_minutes", adminToken, "{\"value\": 1.5}"), "BR-QT-13");

        assertUnchanged(before);
        assertThat(audits()).isEmpty();
    }

    @Test
    void addingOrDeletingConfigsIsNotPossible() {
        assertError(http.exchange(PATH, HttpMethod.POST, json("{\"value\":\"5\"}", adminToken), MAP),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(http.exchange(PATH + "/otp.ttl_minutes", HttpMethod.DELETE, new HttpEntity<>(bearer(adminToken)),
                MAP), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_configs", Integer.class))
                .isEqualTo(ConfigKey.values().length);
    }

    // ================================================================ #32 commit / rollback

    @Test
    void failingAuditRollsBackTheExecutedUpdateAndKeepsTheCache() {
        Snapshot before = snapshot("otp.ttl_minutes");

        withFailingTrigger("INSERT", "audit_logs", "NEW.action = 'SYSTEM_CONFIG_UPDATED'", () ->
                assertError(put("otp.ttl_minutes", adminToken, Map.of("value", "10")),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertUnchanged(before);
        assertThat(audits()).isEmpty();
        assertNotLocked("otp.ttl_minutes");
    }

    @Test
    void failingUpdateLeavesNoAudit() {
        Snapshot before = snapshot("otp.ttl_minutes");

        withFailingTrigger("UPDATE", "system_configs", "NEW.key = 'otp.ttl_minutes'", () ->
                assertError(put("otp.ttl_minutes", adminToken, Map.of("value", "10")),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertUnchanged(before);
        assertThat(audits()).isEmpty();
    }

    /** ADR-0036: commit xong mà reload lỗi → 500; bấm lại cùng giá trị → nhánh trùng giá trị, reload, cache khớp DB. */
    @Test
    void reloadFailureAfterCommitIsHealedByRetryingTheSameValue() {
        failNextFindAll.set(true);

        assertError(put("otp.ttl_minutes", adminToken, Map.of("value", "10")), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'otp.ttl_minutes'",
                String.class)).isEqualTo("10");
        assertThat(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(5);
        assertThat(audits()).hasSize(1);

        assertThat(map(ok(put("otp.ttl_minutes", adminToken, Map.of("value", "10"))))).containsEntry("value", "10");
        assertThat(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(10);
        assertThat(audits()).hasSize(1);
    }

    // ================================================================ #32 đồng thời

    /**
     * G3 (ADR-0036 mục 4): A sửa {@code min_nights = 7}, B sửa {@code max_nights = 5}; mỗi giá trị hợp lệ so với giá trị
     * cũ của khóa kia, cùng nhau thì vi phạm. Cả hai khóa cả cặp theo key tăng dần nên request sau thấy giá trị đã commit
     * của request trước → đúng một 200, một 400. Barrier ở {@code flush()}: nếu ai đó chỉ khóa key của mình, hai request
     * cùng tới flush và cùng commit → test đỏ.
     */
    @Test
    void pairEditedConcurrentlyNeverBreaksTheConstraint() throws Exception {
        flushBarrier = new CyclicBarrier(2);

        List<ResponseEntity<Map<String, Object>>> responses = whileConfigsLocked(
                List.of("boarding.max_nights", "boarding.min_nights"),
                () -> put("boarding.min_nights", adminToken, Map.of("value", "7")),
                () -> put("boarding.max_nights", adminToken, Map.of("value", "5")));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        int minNights = Integer.parseInt(jdbc.queryForObject(
                "SELECT value FROM system_configs WHERE key = 'boarding.min_nights'", String.class));
        int maxNights = Integer.parseInt(jdbc.queryForObject(
                "SELECT value FROM system_configs WHERE key = 'boarding.max_nights'", String.class));
        assertThat(minNights).isLessThanOrEqualTo(maxNights);
        assertThat(configs.getInt(ConfigKey.BOARDING_MIN_NIGHTS))
                .isLessThanOrEqualTo(configs.getInt(ConfigKey.BOARDING_MAX_NIGHTS));
        assertThat(audits()).hasSize(1);
    }

    /** G4: hai PUT cùng key nối tiếp nhau dưới khóa dòng; {@code before} của lần sau là {@code after} của lần trước. */
    @Test
    void sameKeyConcurrentEditsAreSerialisedAndAuditsChain() throws Exception {
        List<ResponseEntity<Map<String, Object>>> responses = whileConfigsLocked(List.of("otp.ttl_minutes"),
                () -> put("otp.ttl_minutes", adminToken, Map.of("value", "10")),
                () -> put("otp.ttl_minutes", adminToken, Map.of("value", "11")));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        List<Map<String, Object>> audits = audits();
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).get("before_data")).isEqualTo("{\"key\": \"otp.ttl_minutes\", \"value\": \"5\"}");
        assertThat(audits.get(1).get("before_data")).isEqualTo(audits.get(0).get("after_data"));
        String last = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'otp.ttl_minutes'",
                String.class);
        assertThat(audits.get(1).get("after_data")).isEqualTo("{\"key\": \"otp.ttl_minutes\", \"value\": \"" + last
                + "\"}");
        assertThat(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).isEqualTo(Integer.parseInt(last));
    }

    // ================================================================ helpers — kỳ vọng độc lập

    private record Expected(List<String> invalid, List<String> valid, String message) {}

    /** Khoảng thực tế độc lập với code: seed V2 + bảng cặp của ADR-0036 (giá trị khóa kia lúc test chạy là seed). */
    private static Expected expected(ConfigKey key, String min, String max, String unit) {
        if (key.type() == ConfigValueType.TIME) {
            return new Expected(List.of("05:59", "22:01", "noon", "12:00:30"), List.of(min, max),
                    "Giá trị của " + key.key() + " phải là giờ dạng HH:mm từ " + min + " đến " + max + " (BR-QT-13)");
        }
        int low = Integer.parseInt(min);
        int high = Integer.parseInt(max);
        String reason = "";
        List<String> valid = new ArrayList<>(List.of(min, max));
        if (key == ConfigKey.VISIT_LATE_PRIORITY_MINUTES) {          // < no_show (30)
            high = 29;
            reason = ": phải nhỏ hơn appointment.no_show_after_minutes đang là 30";
            valid = List.of(min, "29");
        } else if (key == ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES) { // > late_priority (15)
            low = 16;
            reason = ": phải lớn hơn visit.late_priority_minutes đang là 15";
            valid = List.of("16", max);
        } else if (key == ConfigKey.APPOINTMENT_LATE_CANCEL_HOURS) {     // < reminder_hours_before (24), ADR-0056
            high = 23;
            reason = ": phải nhỏ hơn appointment.reminder_hours_before đang là 24";
            valid = List.of(min, "23");
        } else if (key == ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE) { // > late_cancel_hours (12), ADR-0056
            low = 13;
            reason = ": phải lớn hơn appointment.late_cancel_hours đang là 12";
            valid = List.of("13", max);
        }
        List<String> invalid = List.of(String.valueOf(low - 1), String.valueOf(high + 1), "abc", "1.5");
        return new Expected(invalid, valid, "Giá trị của " + key.key() + " phải là số nguyên từ " + low + " đến " + high
                + " (" + unit + ")" + reason + " (BR-QT-13)");
    }

    private String cached(ConfigKey key) {
        return switch (key.type()) {
            case INT -> String.valueOf(configs.getInt(key));
            case TIME -> configs.getTime(key).toString();
            case DECIMAL -> configs.getDecimal(key).toPlainString();
            case BOOL -> String.valueOf(configs.getBool(key));
        };
    }

    // ================================================================ helpers — trạng thái

    private record Snapshot(String key, String value, Object updatedBy, Object updatedAt, String cached) {}

    private Snapshot snapshot(String key) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT value, updated_by, updated_at FROM system_configs WHERE key = ?", key);
        ConfigKey configKey = configKey(key);
        return new Snapshot(key, (String) row.get("value"), row.get("updated_by"), row.get("updated_at"),
                configKey == null ? null : cached(configKey));
    }

    /** Lỗi không để lại gì: DB, cache, audit của action này như cũ. */
    private void assertUnchanged(Snapshot before) {
        Snapshot after = snapshot(before.key());
        assertThat(after).isEqualTo(before);
    }

    private static ConfigKey configKey(String key) {
        for (ConfigKey candidate : ConfigKey.values()) {
            if (candidate.key().equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    private List<Map<String, Object>> audits() {
        return jdbc.queryForList("""
                SELECT entity_type, entity_id, actor_email, before_data::text AS before_data,
                       after_data::text AS after_data
                FROM audit_logs WHERE actor_account_id = ? AND action = 'SYSTEM_CONFIG_UPDATED' ORDER BY id""", admin);
    }

    /** Khóa đã nhả: một transaction khác lấy được khóa ngay ({@code NOWAIT}). */
    private void assertNotLocked(String... keys) {
        tx.executeWithoutResult(status -> {
            for (String key : keys) {
                jdbc.queryForList("SELECT key FROM system_configs WHERE key = ? FOR UPDATE NOWAIT", key);
            }
        });
    }

    // ================================================================ helpers — đồng thời

    /**
     * Giữ khóa các dòng {@code keys} ở một transaction khác, bắn lần lượt từng request và chờ nó bị chặn trên khóa
     * ({@code pg_stat_activity}), rồi commit để nhả. Trả response theo đúng thứ tự request.
     */
    @SafeVarargs
    private List<ResponseEntity<Map<String, Object>>> whileConfigsLocked(List<String> keys,
            Callable<ResponseEntity<Map<String, Object>>>... requests) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.length + 1);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                for (String key : keys.stream().sorted().toList()) {
                    jdbc.queryForList("SELECT key FROM system_configs WHERE key = ? FOR UPDATE", key);
                }
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
            Integer waiting = jdbc.queryForObject(WAITING_ON_CONFIG_LOCK, Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Không thấy " + expected + " request chờ khóa system_configs");
    }

    /** Trigger tạm làm {@code event} vào {@code table} lỗi khi {@code condition} đúng (khuôn {@code StaffAdminIT}). */
    private void withFailingTrigger(String event, String table, String condition, Runnable body) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_config_fail_" + suffix;
        String trigger = "trg_it_config_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE %s ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, event, table, function));
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
        return "config-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "SystemConfigAdminIT")).accessToken();
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> put(String key, String token, Object body) {
        return http.exchange(PATH + "/" + key, HttpMethod.PUT, new HttpEntity<>(body, bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> raw(String key, String token, String body) {
        return http.exchange(PATH + "/" + key, HttpMethod.PUT, json(body, token), MAP);
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
