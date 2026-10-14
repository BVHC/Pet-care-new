package com.petcare.module.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.dto.AuditLogFilter;
import com.petcare.module.identity.repository.AuditLogQuery;
import com.petcare.module.identity.repository.AuditLogQueryRepository;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * T15 phần 3 — {@code GET /api/audit-logs} (identity-v1 #36, UC11; BR-QT-15, 16; docs/adr/0038) qua HTTP trên Postgres 17
 * thật. {@code audit_logs} không xóa được (trigger BR-QT-16): mỗi test chèn dòng ở năm 2003, mỗi test một cửa sổ 2 ngày
 * riêng ({@link #window}) và một dải {@code actor_account_id} riêng (cột không có FK), rồi chỉ đọc lại theo các dấu đó.
 * Câu EXPLAIN chạy trong transaction rollback. {@link AuditLogQueryRepository} là spy gọi bản thật; các test cần chen
 * vào (snapshot, trạng thái transaction, lỗi DB) bật hook.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuditLogViewIT {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String PATH = "/api/audit-logs";
    private static final Instant ERA = Instant.parse("2003-01-01T00:00:00Z");
    private static final List<String> ENTRY_KEYS = List.of("id", "actorAccountId", "actorEmail", "action",
            "entityType", "entityId", "beforeData", "afterData", "reason", "ipAddress", "createdAt");
    private static final String TX_STATE = """
            SELECT current_setting('transaction_isolation') || '|' || current_setting('transaction_read_only')
                   || '|' || pg_backend_pid()
            """;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SystemConfigService configs;

    @MockitoSpyBean
    private AuditLogQueryRepository repository;

    private volatile Runnable afterCount;
    private volatile Runnable beforePage;
    private volatile RuntimeException failNextPage;
    private TransactionTemplate tx;
    private String tag;
    private int index;
    private long branch;
    private long admin;
    private String adminToken;
    private String otpTtl;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        index = SEQ.incrementAndGet();
        tag = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        otpTtl = jdbc.queryForObject("SELECT value FROM system_configs WHERE key = 'otp.ttl_minutes'", String.class);
        branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "AuditLogViewIT " + tag);
        admin = staff("ADMIN", null);
        adminToken = token(admin);

        Answer<?> real = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = real.answer(invocation);
            Runnable hook = afterCount;
            if (hook != null) {
                hook.run();
            }
            return result;
        }).when(repository).count(any());
        doAnswer(invocation -> {
            Runnable hook = beforePage;
            if (hook != null) {
                hook.run();
            }
            RuntimeException failure = failNextPage;
            if (failure != null) {
                failNextPage = null;
                throw failure;
            }
            return real.answer(invocation);
        }).when(repository).findPage(any(), anyLong(), anyInt());
    }

    @AfterEach
    void restore() {
        afterCount = null;
        beforePage = null;
        failNextPage = null;
        jdbc.update("UPDATE system_configs SET value = ?, updated_by = NULL WHERE key = 'otp.ttl_minutes'", otpTtl);
        configs.reload();
    }

    // ================================================================ DTO / mapping

    @Test
    void contractShapeMatchesRowColumnByColumn() {
        long actor = actor(1);
        Instant at = window().plusMillis(1234).plusNanos(567_000);
        long id = insert(actor, "AUDITVIEWIT_SHAPE", "branches", 4242L,
                "{\"chiNhanh\": {\"ten\": \"Chi nhánh Hà Nội\", \"heSo\": 12.3456789012345678}, \"so\": 7}",
                "{\"trangThai\": \"ACTIVE\"}", "lý do kiểm thử", "198.51.100.23", at);

        Map<String, Object> data = ok(list(adminToken, "actorAccountId", actor));
        assertThat(data).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages");
        Map<String, Object> entry = only(data);

        assertThat(entry).containsOnlyKeys(ENTRY_KEYS);
        assertThat(((Number) entry.get("id")).longValue()).isEqualTo(id);
        assertThat(((Number) entry.get("actorAccountId")).longValue()).isEqualTo(actor);
        assertThat(entry.get("actorEmail")).isEqualTo("actor-" + actor + "@petcare.test");
        assertThat(entry.get("action")).isEqualTo("AUDITVIEWIT_SHAPE");
        assertThat(entry.get("entityType")).isEqualTo("branches");
        assertThat(((Number) entry.get("entityId")).longValue()).isEqualTo(4242L);
        assertThat(entry.get("beforeData")).isInstanceOf(Map.class)
                .isEqualTo(Map.of("chiNhanh", Map.of("ten", "Chi nhánh Hà Nội", "heSo", 12.3456789012345678),
                        "so", 7));
        assertThat(entry.get("afterData")).isEqualTo(Map.of("trangThai", "ACTIVE"));
        assertThat(entry.get("reason")).isEqualTo("lý do kiểm thử");
        assertThat(entry.get("ipAddress")).isEqualTo("198.51.100.23");
        assertThat(Instant.parse((String) entry.get("createdAt"))).isEqualTo(at);

        String raw = http.exchange(uri("actorAccountId=" + actor), HttpMethod.GET, new HttpEntity<>(bearer(adminToken)),
                String.class).getBody();
        assertThat(raw).as("số thực giữ đủ chữ số, không qua double").contains("12.3456789012345678");
    }

    @Test
    void nullableColumnsAreNullNotMissing() {
        jdbc.update("INSERT INTO audit_logs (action, created_at) VALUES (?, ?)", "AUDITVIEWIT_NULLS_" + tag,
                Timestamp.from(window()));

        Map<String, Object> entry = only(ok(list(adminToken, "action", "AUDITVIEWIT_NULLS_" + tag,
                "from", iso(window()), "to", iso(window()))));

        assertThat(entry).containsOnlyKeys(ENTRY_KEYS);
        for (String key : List.of("actorAccountId", "actorEmail", "entityType", "entityId", "beforeData", "afterData",
                "reason", "ipAddress")) {
            assertThat(entry).as(key).containsEntry(key, null);
        }
    }

    @Test
    void actorIdSurvivesDeletedAccountAndEmailIsTheOneRecorded() {
        long gone = actor(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Long.class, gone)).isZero();
        insert(gone, "AUDITVIEWIT_GONE", null, null, null, null, null, null, window());

        Map<String, Object> entry = only(ok(list(adminToken, "actorAccountId", gone)));

        assertThat(((Number) entry.get("actorAccountId")).longValue()).isEqualTo(gone);
        assertThat(entry.get("actorEmail")).isEqualTo("actor-" + gone + "@petcare.test");
    }

    @Test
    void realRecorderRowRoundTrips() {
        String next = "5".equals(otpTtl) ? "6" : "5";
        assertThat(http.exchange(PATH.replace("audit-logs", "system-configs/otp.ttl_minutes"), HttpMethod.PUT,
                json("{\"value\": \"" + next + "\"}", adminToken), MAP).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> data = ok(list(adminToken, "actorAccountId", admin, "action", "SYSTEM_CONFIG_UPDATED"));
        Map<String, Object> entry = only(data);

        assertThat(entry.get("entityType")).isEqualTo("system_configs");
        assertThat(entry).containsEntry("entityId", null);
        assertThat(entry.get("actorEmail")).isEqualTo(email(admin));
        assertThat(entry.get("beforeData")).isEqualTo(Map.of("key", "otp.ttl_minutes", "value", otpTtl));
        assertThat(entry.get("afterData")).isEqualTo(Map.of("key", "otp.ttl_minutes", "value", next));
    }

    // ================================================================ lọc, sắp, phân trang

    @Test
    void orderIsCreatedAtDescThenIdDesc() {
        long actor = actor(1);
        long first = insert(actor, "AUDITVIEWIT_ORDER", null, null, null, null, null, null, window());
        long second = insert(actor, "AUDITVIEWIT_ORDER", null, null, null, null, null, null, window());
        long third = insert(actor, "AUDITVIEWIT_ORDER", null, null, null, null, null, null, window());
        long later = insert(actor, "AUDITVIEWIT_ORDER", null, null, null, null, null, null, window().plusSeconds(1));
        long earlier = insert(actor, "AUDITVIEWIT_ORDER", null, null, null, null, null, null,
                window().minusSeconds(1));

        assertThat(ids(ok(list(adminToken, "actorAccountId", actor)))).containsExactly(later, third, second, first,
                earlier);
    }

    @Test
    void everyFilterNarrowsAndRangeIsClosed() {
        long actor = actor(1);
        long other = actor(2);
        Instant from = window();
        Instant to = window().plusSeconds(3600);
        long atFrom = insert(actor, "AUDITVIEWIT_A", "accounts", 11L, null, null, null, null, from);
        long atTo = insert(actor, "AUDITVIEWIT_B", "accounts", 12L, null, null, null, null, to);
        long before = insert(actor, "AUDITVIEWIT_A", "accounts", 11L, null, null, null, null, from.minusNanos(1000));
        long after = insert(actor, "AUDITVIEWIT_A", "accounts", 11L, null, null, null, null, to.plusNanos(1000));
        long otherActor = insert(other, "AUDITVIEWIT_A", "branches", 11L, null, null, null, null, from);

        assertThat(ids(ok(list(adminToken, "actorAccountId", actor)))).containsExactlyInAnyOrder(atFrom, atTo, before,
                after);
        assertThat(ids(ok(list(adminToken, "actorAccountId", actor, "from", iso(from), "to", iso(to)))))
                .as("mốc from và to được tính, ngoài 1 µs thì không").containsExactlyInAnyOrder(atFrom, atTo);
        assertThat(ids(ok(list(adminToken, "actorAccountId", actor, "action", "AUDITVIEWIT_A"))))
                .containsExactlyInAnyOrder(atFrom, before, after);
        assertThat(ids(ok(list(adminToken, "actorAccountId", actor, "entityType", "accounts", "entityId", 12))))
                .containsExactly(atTo);
        assertThat(ids(ok(list(adminToken, "action", "AUDITVIEWIT_A", "from", iso(from), "to", iso(to)))))
                .as("action trong khoảng ≤ 31 ngày, không mốc").containsExactlyInAnyOrder(atFrom, otherActor);
        assertThat(ids(ok(list(adminToken, "from", iso(from), "to", iso(from)))))
                .as("from == to hợp lệ, khớp đúng thời điểm").containsExactlyInAnyOrder(atFrom, otherActor);
        assertThat(ids(ok(list(adminToken, "from", iso(from), "to", iso(to), "entityType", "branches"))))
                .containsExactly(otherActor);
    }

    @Test
    void offsetsOtherThanUtcMeanTheSameInstant() {
        long actor = actor(1);
        long id = insert(actor, "AUDITVIEWIT_TZ", null, null, null, null, null, null, window());
        String vn = window().atOffset(ZoneOffset.ofHours(7)).toString();

        assertThat(ids(ok(list(adminToken, "actorAccountId", actor, "from", vn, "to", vn)))).containsExactly(id);
    }

    @Test
    void blankActionAndEntityTypeMeanNotSent() {
        long actor = actor(1);
        long id = insert(actor, "AUDITVIEWIT_BLANK", null, null, null, null, null, null, window());

        assertThat(ids(ok(get(adminToken, "actorAccountId=" + actor + "&action=&entityType=")))).containsExactly(id);
        assertThat(ids(ok(get(adminToken, "actorAccountId=" + actor + "&action=%20&entityId=")))).containsExactly(id);
    }

    @Test
    void unknownValuesGiveAnEmptyPageNever404() {
        long actor = actor(1);
        insert(actor, "AUDITVIEWIT_KNOWN", "accounts", 1L, null, null, null, null, window());

        for (Map<String, Object> data : List.of(
                ok(list(adminToken, "actorAccountId", actor, "action", "KHONG_CO")),
                ok(list(adminToken, "actorAccountId", actor, "entityType", "khong_co")),
                ok(list(adminToken, "actorAccountId", -1)),
                ok(list(adminToken, "entityType", "khong_co_" + tag, "entityId", 0)),
                ok(get(adminToken, "actorAccountId=" + actor + "&action=A&action=B")))) {
            assertThat((List<?>) data.get("content")).isEmpty();
            assertThat(((Number) data.get("totalElements")).longValue()).isZero();
        }
    }

    @Test
    void pagingIsClampedAndMetadataConsistent() {
        long actor = actor(1);
        long a = insert(actor, "AUDITVIEWIT_PAGE", null, null, null, null, null, null, window());
        long b = insert(actor, "AUDITVIEWIT_PAGE", null, null, null, null, null, null, window().plusSeconds(1));
        long c = insert(actor, "AUDITVIEWIT_PAGE", null, null, null, null, null, null, window().plusSeconds(2));

        Map<String, Object> second = ok(list(adminToken, "actorAccountId", actor, "page", 1, "size", 2));
        assertThat(ids(second)).containsExactly(a);
        assertThat(second).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 2);

        Map<String, Object> huge = ok(list(adminToken, "actorAccountId", actor, "page", -1, "size", 1000));
        assertThat(huge).containsEntry("page", 0).containsEntry("size", 100);
        assertThat(ids(huge)).containsExactly(c, b, a);

        Map<String, Object> zero = ok(list(adminToken, "actorAccountId", actor, "size", 0));
        assertThat(zero).containsEntry("size", 1).containsEntry("totalPages", 3);
        assertThat(ids(zero)).containsExactly(c);

        Map<String, Object> defaults = ok(list(adminToken, "actorAccountId", actor, "sort", "id,asc"));
        assertThat(defaults).as("sort bị bỏ qua").containsEntry("page", 0).containsEntry("size", 20);
        assertThat(ids(defaults)).containsExactly(c, b, a);
    }

    @Test
    void adminSeesRowsOfEveryActorIncludingSystemAndSelf() {
        long otherAdmin = staff("ADMIN", null);
        long superManager = staff("SUPER_MANAGER", null);
        String type = "it_" + tag;
        long ofOther = insert(otherAdmin, "AUDITVIEWIT_WHO", type, 1L, null, null, null, null, window());
        long ofSuper = insert(superManager, "AUDITVIEWIT_WHO", type, 1L, null, null, null, null, window());
        long ofSelf = insert(admin, "AUDITVIEWIT_WHO", type, 1L, null, null, null, null, window());
        long ofSystem = jdbc.queryForObject("""
                INSERT INTO audit_logs (action, entity_type, entity_id, created_at) VALUES (?, ?, 1, ?) RETURNING id
                """, Long.class, "AUDITVIEWIT_WHO", type, Timestamp.from(window()));

        assertThat(ids(ok(list(adminToken, "entityType", type, "entityId", 1))))
                .containsExactlyInAnyOrder(ofOther, ofSuper, ofSelf, ofSystem);
    }

    // ================================================================ validate + exception

    @Test
    void crossParameterRulesAre400WithParameterNamesInRuleOrder() {
        Instant from = window();
        String r1 = "to: Thời điểm kết thúc không được trước thời điểm bắt đầu";
        String r2 = "entityType: Bắt buộc khi lọc theo entityId";
        String r3 = "from: Lọc theo hành động cần from và to cách nhau tối đa 31 ngày, hoặc kèm actorAccountId hay "
                + "entityType + entityId";

        assertError(list(adminToken, "from", iso(from.plusSeconds(1)), "to", iso(from)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", r1);
        assertError(list(adminToken, "entityId", 5), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", r2);
        assertError(list(adminToken, "action", "LOGIN_FAILED"), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", r3);
        assertError(list(adminToken, "action", "LOGIN_FAILED", "from", iso(from)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", r3);
        assertError(list(adminToken, "action", "LOGIN_FAILED", "from", iso(from), "to",
                iso(from.plus(Duration.ofDays(31)).plusSeconds(1))), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", r3);
        assertThat(list(adminToken, "action", "LOGIN_FAILED", "from", iso(from), "to",
                iso(from.plus(Duration.ofDays(31)))).getStatusCode()).as("đúng 31 ngày").isEqualTo(HttpStatus.OK);
        assertError(list(adminToken, "action", "LOGIN_FAILED", "entityId", 5), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", r2 + "; " + r3);
        assertError(list(adminToken, "action", "X_Y", "entityId", 5, "from", iso(from.plusSeconds(1)), "to",
                iso(from)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", r1 + "; " + r2);
    }

    @Test
    void malformedParametersAre400Malformed() {
        assertError(get(adminToken, "from=abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'from' sai kiểu dữ liệu");
        assertError(get(adminToken, "to=2026-10-05T08:30:00"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'to' sai kiểu dữ liệu");
        assertError(get(adminToken, "from=2026-10-05T08:30:00+07:00"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'from' sai kiểu dữ liệu");
        assertThat(get(adminToken, "from=2026-10-05T08:30:00%2B07:00").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertError(get(adminToken, "actorAccountId=x"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'actorAccountId' sai kiểu dữ liệu");
        assertError(get(adminToken, "entityType=accounts&entityId=1.5"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'entityId' sai kiểu dữ liệu");
        assertError(get(adminToken, "size=x"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'size' sai kiểu dữ liệu");
    }

    @Test
    void tooLongActionOrEntityTypeIs400Validation() {
        assertError(list(adminToken, "action", "A".repeat(51)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "action: Mã hành động tối đa 50 ký tự");
        assertError(list(adminToken, "entityType", "a".repeat(51)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "entityType: Loại đối tượng tối đa 50 ký tự");
        assertThat(list(adminToken, "actorAccountId", actor(1), "action", "A".repeat(50)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void writeMethodsAreRejected() {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            HttpEntity<?> request = method == HttpMethod.DELETE ? new HttpEntity<>(bearer(adminToken))
                    : json("{}", adminToken);
            assertError(http.exchange(PATH, method, request, MAP), HttpStatus.METHOD_NOT_ALLOWED,
                    "METHOD_NOT_ALLOWED", null);
            assertError(http.exchange(PATH + "/1", method, request, MAP), HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND", null);
        }
    }

    // ================================================================ phân quyền

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER", "CUSTOMER"})
    void nonAdminIs403WithoutAudit(String role) {
        long actor = "CUSTOMER".equals(role) ? customerAccount() : staff(role, "SUPER_MANAGER".equals(role)
                ? null : branch);
        String actorToken = token(actor);
        long maxBefore = maxAuditId();

        assertError(list(actorToken), HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");
        assertError(list(actorToken, "entityType", "accounts", "entityId", actor), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Không có quyền thực hiện thao tác này");

        assertThat(maxAuditId()).isEqualTo(maxBefore);
    }

    @Test
    void formErrorsComeBeforeRoleButRuleErrorsAfter() {
        String superToken = token(staff("SUPER_MANAGER", null));

        assertError(get(superToken, "from=abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'from' sai kiểu dữ liệu");
        assertError(list(superToken, "action", "A".repeat(51)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", null);
        assertError(list(superToken, "entityId", 5), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);
        assertError(list(superToken, "from", iso(window().plusSeconds(1)), "to", iso(window())), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", null);
    }

    @Test
    void invalidSessionsAre401() {
        assertError(list(null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        assertError(list("rac.rac.rac"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long loggedOut = staff("ADMIN", null);
        String loggedOutToken = token(loggedOut);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", loggedOut);
        assertError(list(loggedOutToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long expired = staff("ADMIN", null);
        String expiredToken = token(expired);
        jdbc.update("UPDATE sessions SET expires_at = now() - interval '1 minute' WHERE account_id = ?", expired);
        assertError(list(expiredToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long locked = staff("ADMIN", null);
        String lockedToken = token(locked);
        jdbc.update("UPDATE accounts SET is_locked = true, locked_reason = 'IT' WHERE id = ?", locked);
        assertError(list(lockedToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

        long disabled = staff("ADMIN", null);
        String disabledToken = token(disabled);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", disabled);
        assertError(list(disabledToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
    }

    @Test
    void adminWhoMustChangePasswordIsBlocked() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", admin);

        ResponseEntity<Map<String, Object>> response = list(adminToken);

        assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
        assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
    }

    @Test
    void roleIsReadFromTheDatabaseOnEveryRequest() {
        long actor = actor(1);
        insert(actor, "AUDITVIEWIT_ROLE", null, null, null, null, null, null, window());
        assertThat(list(adminToken, "actorAccountId", actor).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE accounts SET role = 'SUPER_MANAGER' WHERE id = ?", admin);
        assertError(list(adminToken, "actorAccountId", actor), HttpStatus.FORBIDDEN, "ACCESS_DENIED", null);

        jdbc.update("UPDATE accounts SET role = 'ADMIN' WHERE id = ?", admin);
        assertThat(list(adminToken, "actorAccountId", actor).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ================================================================ transaction

    @Test
    void getWritesNothing() {
        long actor = actor(1);
        insert(actor, "AUDITVIEWIT_NOWRITE", null, null, null, null, null, null, window());
        String superToken = token(staff("SUPER_MANAGER", null));
        long maxBefore = maxAuditId();

        assertThat(list(adminToken, "actorAccountId", actor).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list(adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list(adminToken, "entityId", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list(superToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        failNextPage = new DataAccessResourceFailureException("IT: mất kết nối");
        assertThat(list(adminToken, "actorAccountId", actor).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(maxAuditId()).as("xem không ghi audit (Q5)").isEqualTo(maxBefore);
    }

    @Test
    void queriesRunInsideOneReadOnlyRepeatableReadTransaction() {
        long actor = actor(1);
        insert(actor, "AUDITVIEWIT_TX", null, null, null, null, null, null, window());
        List<String> states = new ArrayList<>();
        afterCount = () -> states.add(namedJdbc.queryForObject(TX_STATE, new MapSqlParameterSource(), String.class));
        beforePage = () -> states.add(namedJdbc.queryForObject(TX_STATE, new MapSqlParameterSource(), String.class));

        assertThat(list(adminToken, "actorAccountId", actor).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(states).hasSize(2);
        assertThat(states.get(0)).startsWith("repeatable read|on|");
        assertThat(states.get(1)).as("cùng connection, cùng transaction").isEqualTo(states.get(0));
    }

    @Test
    void countAndPageSeeOneSnapshot() {
        long actor = actor(1);
        long existing = insert(actor, "AUDITVIEWIT_SNAP", null, null, null, null, null, null, window());
        List<Long> injected = new ArrayList<>();
        afterCount = () -> {
            afterCount = null;
            injected.add(autocommitInsert(actor, window().plusSeconds(5)));
        };

        Map<String, Object> data = ok(list(adminToken, "actorAccountId", actor));

        assertThat(injected).hasSize(1);
        assertThat(ids(data)).as("dòng commit sau câu đếm không lọt vào trang").containsExactly(existing);
        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(1);

        Map<String, Object> again = ok(list(adminToken, "actorAccountId", actor));
        assertThat(ids(again)).containsExactly(injected.get(0), existing);
        assertThat(((Number) again.get("totalElements")).longValue()).isEqualTo(2);
    }

    @Test
    void connectionsReturnToPoolAsReadCommittedReadWrite() throws Exception {
        long actor = actor(1);
        insert(actor, "AUDITVIEWIT_POOL", null, null, null, null, null, null, window());
        for (int i = 0; i < 5; i++) {
            assertThat(list(adminToken, "actorAccountId", actor).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(list(adminToken, "entityId", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                borrowed.add(hikari.getConnection());
            }
            for (Connection connection : borrowed) {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(connection.isReadOnly()).isFalse();
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery(
                                "SELECT current_setting('transaction_isolation'), "
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

    @Test
    void failedQueryIs500AndReleasesTheConnection() throws Exception {
        long actor = actor(1);
        long id = insert(actor, "AUDITVIEWIT_FAIL", null, null, null, null, null, null, window());
        failNextPage = new DataAccessResourceFailureException("IT: DB tạm mất kết nối");

        assertError(list(adminToken, "actorAccountId", actor), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                null);

        assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(ids(ok(list(adminToken, "actorAccountId", actor)))).containsExactly(id);
    }

    // ================================================================ index

    /**
     * Mỗi tổ hợp lọc chính dùng đúng index (docs/adr/0038 mục 6). 60 000 dòng trải 2001–2002 chèn trong một transaction
     * rollback (bảng không xóa được), {@code ANALYZE} trong cùng transaction thấy chúng; EXPLAIN đúng chuỗi SQL của
     * {@link AuditLogQuery} với tham số bind thật.
     */
    @Test
    void queriesUseExpectedIndex() {
        Instant day = Instant.parse("2002-01-10T00:00:00Z");
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO audit_logs (actor_account_id, actor_email, action, entity_type, entity_id,
                                            after_data, created_at)
                    SELECT 900000000 + g % 500, 'explain' || (g % 500) || '@petcare.test',
                           'EXPLAIN_ACTION_' || (g % 40), 'explain_type_' || (g % 10), g % 2000,
                           '{"k": 1}'::jsonb, timestamptz '2001-01-01 00:00:00+00' + g * interval '17 minutes'
                    FROM generate_series(1, 60000) g
                    """);
            jdbc.execute("ANALYZE audit_logs");

            expectIndex(new AuditLogFilter(null, null, null, null, null, null), "ix_audit_logs_created_at", null);
            expectIndex(new AuditLogFilter(null, null, null, null, day, day.plusSeconds(86_400)),
                    "ix_audit_logs_created_at", "ix_audit_logs_created_at");
            expectIndex(new AuditLogFilter(900000007L, null, null, null, null, null),
                    "ix_audit_logs_actor_created_at", "ix_audit_logs_actor_created_at");
            expectIndex(new AuditLogFilter(900000007L, null, null, null, day, day.plus(Duration.ofDays(30))),
                    "ix_audit_logs_actor_created_at", "ix_audit_logs_actor_created_at");
            expectIndex(new AuditLogFilter(null, null, "explain_type_3", 1503L, null, null), "ix_audit_logs_entity",
                    "ix_audit_logs_entity");
            expectIndex(new AuditLogFilter(null, "EXPLAIN_ACTION_5", null, null, day, day.plus(Duration.ofDays(31))),
                    "ix_audit_logs_created_at", "ix_audit_logs_created_at");
            String typeOnly = explain(AuditLogQuery.of(new AuditLogFilter(null, null, "explain_type_3", null, null,
                    null)), true);
            assertThat(typeOnly).as("chỉ entityType").doesNotContain("Seq Scan");

            status.setRollbackOnly();
        });
    }

    // ---------------------------------------------------------------- helpers

    private void expectIndex(AuditLogFilter filter, String pageIndex, String countIndex) {
        AuditLogQuery query = AuditLogQuery.of(filter);
        String page = explain(query, true);
        assertThat(page).as("trang %s\n%s", filter, page).contains(pageIndex).doesNotContain("Seq Scan");
        if (countIndex != null) {
            String count = explain(query, false);
            assertThat(count).as("đếm %s\n%s", filter, count).contains(countIndex).doesNotContain("Seq Scan");
        }
    }

    private String explain(AuditLogQuery query, boolean page) {
        String sql = page ? query.pageSql() : query.countSql();
        MapSqlParameterSource params = page ? query.pageParams(0, 20) : query.params();
        return String.join("\n", namedJdbc.queryForList("EXPLAIN " + sql, params, String.class));
    }

    /** Dòng audit ở cửa sổ riêng của test (năm 2003, 2 ngày / test). */
    private long insert(long actor, String action, String entityType, Long entityId, String before, String after,
            String reason, String ip, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO audit_logs (actor_account_id, actor_email, action, entity_type, entity_id, before_data,
                                        after_data, reason, ip_address, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?) RETURNING id
                """, Long.class, actor, "actor-" + actor + "@petcare.test", action, entityType, entityId, before,
                after, reason, ip, Timestamp.from(createdAt));
    }

    /** Ghi bằng connection riêng, autocommit — ngoài transaction của request. */
    private long autocommitInsert(long actor, Instant createdAt) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            try (ResultSet rs = statement.executeQuery("INSERT INTO audit_logs (actor_account_id, actor_email, "
                    + "action, created_at) VALUES (" + actor + ", 'actor-" + actor + "@petcare.test', "
                    + "'AUDITVIEWIT_SNAP', '" + createdAt + "') RETURNING id")) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Instant window() {
        return ERA.plus(Duration.ofDays(2L * index)).plusSeconds(3600);
    }

    /** {@code actor_account_id} riêng của test (cột không có FK, không trùng tài khoản thật nào). */
    private long actor(int k) {
        return 8_000_000_000L + index * 100L + k;
    }

    private long maxAuditId() {
        return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM audit_logs", Long.class);
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

    private long customerAccount() {
        return jdbc.queryForObject("""
                INSERT INTO accounts (email, password_hash, role, status)
                VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                """, Long.class, newEmail());
    }

    private String newEmail() {
        return "audit-view-it-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@petcare.test";
    }

    private String email(long accountId) {
        return jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, accountId);
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "AuditLogViewIT")).accessToken();
    }

    /** Query string từ cặp tên / giá trị, mỗi giá trị URL-encode ({@code +} → {@code %2B}). */
    private ResponseEntity<Map<String, Object>> list(String token, Object... pairs) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(pairs[i]).append('=')
                    .append(URLEncoder.encode(String.valueOf(pairs[i + 1]), StandardCharsets.UTF_8));
        }
        return get(token, query.toString());
    }

    /** Query string nguyên văn (không encode thêm). */
    private ResponseEntity<Map<String, Object>> get(String token, String rawQuery) {
        return http.exchange(uri(rawQuery), HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private URI uri(String rawQuery) {
        return URI.create(http.getRootUri() + PATH + (rawQuery.isEmpty() ? "" : "?" + rawQuery));
    }

    private static String iso(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsOnlyKeys("data", "message", "code");
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> only(Map<String, Object> data) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");
        assertThat(content).hasSize(1);
        return content.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Map<String, Object> data) {
        return ((List<Map<String, Object>>) data.get("content")).stream()
                .map(entry -> ((Number) entry.get("id")).longValue()).toList();
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode, String message) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("success", "errorCode", "message", "statusCode", "timestamp",
                        "traceId")
                .containsEntry("success", false).containsEntry("errorCode", errorCode)
                .containsEntry("statusCode", status.value());
        assertThat((String) response.getBody().get("message")).isNotBlank();
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
        if (message != null) {
            assertThat(response.getBody()).containsEntry("message", message);
        }
    }
}
