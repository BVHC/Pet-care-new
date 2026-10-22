package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.jpa.repository.Query;
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

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.module.care.service.InAppNotificationDeliveryService;
import com.petcare.module.care.service.NotificationDispatchService.DispatchResult;
import com.petcare.module.identity.service.SessionService;
import com.petcare.support.MutableClock;

/**
 * T15 phần 4 — thông báo trong app của người đang đăng nhập, care-v1 #5–8 (UC88; docs/adr/0039) qua HTTP trên
 * Postgres 17 thật. Mỗi test tạo tài khoản riêng bằng SQL (email có {@link #tag}) và chỉ đọc lại thông báo của các tài
 * khoản đó — {@code InAppNotificationIT} xóa cả bảng ở {@code @BeforeEach} nên không dựa vào số dòng toàn bảng. Mỗi cột
 * của dòng seed một giá trị riêng để bắt map nhầm nguồn. Câu EXPLAIN chạy trong transaction rollback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MyNotificationIT.TestBeans.class})
class MyNotificationIT {

    private static final Instant T0 = Instant.parse("2026-10-14T02:00:00Z");
    private static final Instant SEEDED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final String PATH = "/api/me/notifications";
    private static final String APP_TEMPLATE = "IT_INAPP_NOTICE_APP";
    private static final List<String> ITEM_KEYS = List.of("notificationId", "type", "title", "body", "linkUrl",
            "readAt", "createdAt");
    private static final List<String> PAGE_KEYS = List.of("content", "page", "size", "totalElements", "totalPages");
    private static final List<String> ROLES = List.of("CUSTOMER", "ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER",
            "RECEPTIONIST", "VET", "CARETAKER");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final AtomicInteger SEQ = new AtomicInteger();

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

    @Autowired
    private NotificationApi notificationApi;

    @Autowired
    private InAppNotificationDeliveryService delivery;

    private TransactionTemplate tx;
    private String tag;
    private long branch;
    private long me;
    private String myToken;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        tx = new TransactionTemplate(transactionManager);
        tag = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        branch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.762622, 106.660172, 'ACTIVE') RETURNING id
                """, Long.class, "MyNotificationIT " + tag);
        me = account("CUSTOMER");
        myToken = token(me);
    }

    // ---------------------------------------------------------------- #5 danh sách

    @Test
    void listReturnsOnlyMineNewestFirstWithEveryContractField() {
        long other = account("VET");
        long oldest = insert(me, "TYPE_OLDEST", "Tiêu đề 1", "Nội dung 1", "/link/1", SEEDED_AT, at(1));
        long tieLow = insert(me, "TYPE_TIE_A", "Tiêu đề 2", "Nội dung 2", null, null, at(5));
        long tieHigh = insert(me, "TYPE_TIE_B", "Tiêu đề 3", "Nội dung 3", "/link/3", null, at(5));
        long newest = insert(me, "TYPE_NEWEST", "Tiêu đề 4", "Nội dung 4", "/link/4", null, at(9));
        insert(other, "TYPE_OTHER", "Của người khác", "Không được thấy", null, null, at(20));

        Map<String, Object> data = ok(get(myToken, ""));

        assertThat(data).containsOnlyKeys(PAGE_KEYS);
        assertThat(ids(data)).containsExactly(newest, tieHigh, tieLow, oldest);
        assertThat(data).containsEntry("page", 0).containsEntry("size", 20).containsEntry("totalElements", 4)
                .containsEntry("totalPages", 1);
        Map<String, Object> first = items(data).get(3);
        assertThat(first).containsOnlyKeys(ITEM_KEYS)
                .containsEntry("notificationId", (int) oldest)
                .containsEntry("type", "TYPE_OLDEST").containsEntry("title", "Tiêu đề 1")
                .containsEntry("body", "Nội dung 1").containsEntry("linkUrl", "/link/1");
        assertThat(Instant.parse((String) first.get("readAt"))).isEqualTo(SEEDED_AT);
        assertThat(Instant.parse((String) first.get("createdAt"))).isEqualTo(at(1));
        Map<String, Object> unreadWithoutLink = items(data).get(2);
        assertThat(unreadWithoutLink).containsOnlyKeys(ITEM_KEYS).containsEntry("linkUrl", null)
                .containsEntry("readAt", null);
    }

    @Test
    void unreadOnlyFiltersAndBlankOrFalseMeansEverything() {
        long read = insert(me, "T", "a", "b", null, SEEDED_AT, at(1));
        long unread = insert(me, "T", "c", "d", null, null, at(2));

        assertThat(ids(ok(get(myToken, "unreadOnly=true")))).containsExactly(unread);
        assertThat(ok(get(myToken, "unreadOnly=true"))).containsEntry("totalElements", 1);
        assertThat(ids(ok(get(myToken, "unreadOnly=false")))).containsExactly(unread, read);
        assertThat(ids(ok(get(myToken, "unreadOnly=")))).containsExactly(unread, read);
    }

    @Test
    void pagesAndClampsPageAndSize() {
        long a = insert(me, "T", "a", "b", null, null, at(1));
        long b = insert(me, "T", "a", "b", null, null, at(2));
        long c = insert(me, "T", "a", "b", null, null, at(3));

        Map<String, Object> second = ok(get(myToken, "page=1&size=2"));
        assertThat(ids(second)).containsExactly(a);
        assertThat(second).containsEntry("page", 1).containsEntry("size", 2).containsEntry("totalElements", 3)
                .containsEntry("totalPages", 2);
        assertThat(ok(get(myToken, "size=1000"))).containsEntry("size", 100);
        assertThat(ok(get(myToken, "size=0"))).containsEntry("size", 1);
        Map<String, Object> negative = ok(get(myToken, "page=-3&size=-1"));
        assertThat(negative).containsEntry("page", 0).containsEntry("size", 1);
        assertThat(ids(negative)).containsExactly(c);
        assertThat(ids(ok(get(myToken, "page=5")))).isEmpty();
        assertThat(ids(ok(get(myToken, "")))).containsExactly(c, b, a);
    }

    @Test
    void accountIdInTheQueryIsIgnored() {
        long other = account("RECEPTIONIST");
        long theirs = insert(other, "T", "a", "b", null, null, at(1));
        long mine = insert(me, "T", "a", "b", null, null, at(1));

        assertThat(ids(ok(get(myToken, "accountId=" + other)))).containsExactly(mine).doesNotContain(theirs);
    }

    @Test
    void emptyListIsAnEmptyArray() {
        Map<String, Object> data = ok(get(myToken, ""));

        assertThat(data.get("content")).isEqualTo(List.of());
        assertThat(data).containsEntry("totalElements", 0).containsEntry("totalPages", 0);
    }

    // ---------------------------------------------------------------- #6 đếm

    @Test
    void unreadCountCountsOnlyMyUnread() {
        long other = account("CARETAKER");
        insert(me, "T", "a", "b", null, null, at(1));
        insert(me, "T", "a", "b", null, null, at(2));
        insert(me, "T", "a", "b", null, SEEDED_AT, at(3));
        insert(other, "T", "a", "b", null, null, at(4));

        Map<String, Object> data = ok(get(myToken, "/unread-count", ""));

        assertThat(data).containsOnlyKeys("count").containsEntry("count", 2);
        assertThat(ok(get(token(other), "/unread-count", ""))).containsEntry("count", 1);
        assertThat(ok(get(token(account("ADMIN")), "/unread-count", ""))).containsEntry("count", 0);
    }

    // ---------------------------------------------------------------- #7 đánh dấu một

    @Test
    void markReadSetsReadAtAndUpdatedAtFromTheClockAndKeepsTheFirstReadOnRepeat() {
        long id = insert(me, "T", "a", "b", null, null, at(1));
        clock.set(T0.plusSeconds(100));

        assertThat(markRead(myToken, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(id)).isEqualTo(T0.plusSeconds(100));
        assertThat(updatedAt(id)).isEqualTo(T0.plusSeconds(100));
        assertThat(ok(get(myToken, "/unread-count", ""))).containsEntry("count", 0);

        clock.set(T0.plusSeconds(500));
        ResponseEntity<String> again = markRead(myToken, id);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(again.getBody()).isNull();
        assertThat(readAt(id)).isEqualTo(T0.plusSeconds(100));
        assertThat(updatedAt(id)).isEqualTo(T0.plusSeconds(100));
    }

    @Test
    void markReadOfSomeoneElsesNotificationIs404LikeAMissingOneAndChangesNothing() {
        long other = account("VET");
        long theirs = insert(other, "T", "a", "b", null, null, at(1));
        Instant theirUpdatedAt = updatedAt(theirs);
        long missing = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000000 FROM notifications", Long.class);

        ResponseEntity<Map<String, Object>> foreign = post(myToken, "/" + theirs + "/read", null, null);
        ResponseEntity<Map<String, Object>> absent = post(myToken, "/" + missing + "/read", null, null);

        assertError(foreign, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy thông báo #" + theirs);
        assertError(absent, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy thông báo #" + missing);
        assertThat(readAt(theirs)).isNull();
        assertThat(updatedAt(theirs)).isEqualTo(theirUpdatedAt);
        assertThat(ok(get(token(other), "/unread-count", ""))).containsEntry("count", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveIdIs404(String id) {
        assertError(post(myToken, "/" + id + "/read", null, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Không tìm thấy thông báo #" + id);
    }

    @Test
    void markReadIgnoresAnyBody() {
        long id = insert(me, "T", "a", "b", null, null, at(1));

        ResponseEntity<Map<String, Object>> response = post(myToken, "/" + id + "/read", "{\"accountId\": 1}",
                MediaType.APPLICATION_JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(id)).isEqualTo(T0);
        long second = insert(me, "T", "a", "b", null, null, at(2));
        assertThat(post(myToken, "/" + second + "/read", "rác", MediaType.TEXT_PLAIN).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------- #8 đánh dấu tất cả

    @Test
    void readAllMarksOnlyMyUnreadAndLeavesEarlierReadsAndOthersAlone() {
        long other = account("BRANCH_MANAGER");
        long u1 = insert(me, "T", "a", "b", null, null, at(1));
        long u2 = insert(me, "T", "a", "b", null, null, at(2));
        long alreadyRead = insert(me, "T", "a", "b", null, SEEDED_AT, at(3));
        Instant alreadyReadUpdatedAt = updatedAt(alreadyRead);
        long theirs = insert(other, "T", "a", "b", null, null, at(4));
        clock.set(T0.plusSeconds(60));

        ResponseEntity<Map<String, Object>> response = post(myToken, "/read-all", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(u1)).isEqualTo(T0.plusSeconds(60));
        assertThat(readAt(u2)).isEqualTo(T0.plusSeconds(60));
        assertThat(updatedAt(u1)).isEqualTo(T0.plusSeconds(60));
        assertThat(readAt(alreadyRead)).isEqualTo(SEEDED_AT);
        assertThat(updatedAt(alreadyRead)).isEqualTo(alreadyReadUpdatedAt);
        assertThat(readAt(theirs)).isNull();
        assertThat(ok(get(myToken, "/unread-count", ""))).containsEntry("count", 0);

        clock.set(T0.plusSeconds(120));
        assertThat(post(myToken, "/read-all", null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(u1)).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void notificationArrivingAfterReadAllStaysUnread() {
        insert(me, "T", "a", "b", null, null, at(1));
        assertThat(post(myToken, "/read-all", null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        long later = insert(me, "T", "a", "b", null, null, at(2));

        assertThat(readAt(later)).isNull();
        assertThat(ok(get(myToken, "/unread-count", ""))).containsEntry("count", 1);
        assertThat(ids(ok(get(myToken, "unreadOnly=true")))).containsExactly(later);
    }

    @Test
    void readAllWithNothingUnreadIs204() {
        assertThat(post(myToken, "/read-all", null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---------------------------------------------------------------- dòng do worker ST20 thật giao

    @Test
    void notificationDeliveredByTheInAppWorkerIsReadable() {
        jdbc.update("""
                INSERT INTO notification_templates (code, channel, subject, body, default_subject, default_body,
                                                    allowed_vars, required_vars)
                VALUES (?, 'IN_APP', 'Thông báo {so}', 'Nội dung số {so}', 'Thông báo {so}', 'Nội dung số {so}',
                        '["so"]'::jsonb, '["so"]'::jsonb)
                ON CONFLICT (code) DO NOTHING
                """, APP_TEMPLATE);
        tx.executeWithoutResult(status -> notificationApi.enqueue(new NotificationRequest(APP_TEMPLATE,
                Channel.IN_APP, me, null, Map.of("so", 42), "/me/appointments/42")));
        for (int i = 0; i < 1000 && delivery.deliverNext(clock.instant()) != DispatchResult.NONE; i++) {
            // giao hết dòng IN_APP đến hạn, kể cả của test khác
        }

        Map<String, Object> item = items(ok(get(myToken, ""))).get(0);

        assertThat(item).containsEntry("type", "IT_INAPP_NOTICE").containsEntry("title", "Thông báo 42")
                .containsEntry("body", "Nội dung số 42").containsEntry("linkUrl", "/me/appointments/42")
                .containsEntry("readAt", null);
        assertThat(Instant.parse((String) item.get("createdAt"))).isEqualTo(T0);
        assertThat(ok(get(myToken, "/unread-count", ""))).containsEntry("count", 1);
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void everyRoleCanUseAllFourEndpointsOnItsOwnNotifications() {
        for (String role : ROLES) {
            long account = account(role);
            String token = token(account);
            long id = insert(account, "T_" + role, "a", "b", null, null, at(1));
            insert(account, "T_" + role, "a", "b", null, null, at(2));

            assertThat(ids(ok(get(token, "")))).as(role).hasSize(2).contains(id);
            assertThat(ok(get(token, "/unread-count", ""))).as(role).containsEntry("count", 2);
            assertThat(markRead(token, id).getStatusCode()).as(role).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(post(token, "/read-all", null, null).getStatusCode()).as(role)
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(ok(get(token, "/unread-count", ""))).as(role).containsEntry("count", 0);
        }
    }

    @Test
    void roleChangedInTheDatabaseStillSeesOnlyOwnNotifications() {
        long staff = account("VET");
        String token = token(staff);
        long mine = insert(staff, "T", "a", "b", null, null, at(1));
        insert(me, "T", "a", "b", null, null, at(2));
        jdbc.update("UPDATE accounts SET role = 'CARETAKER' WHERE id = ?", staff);

        assertThat(ids(ok(get(token, "")))).containsExactly(mine);
    }

    @Test
    void withoutValidTokenEveryEndpointIs401() {
        long id = insert(me, "T", "a", "b", null, null, at(1));
        for (String token : new String[] {null, "garbage"}) {
            assertError(get(token, ""), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
            assertError(get(token, "/unread-count", ""), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
            assertError(post(token, "/" + id + "/read", null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    null);
            assertError(post(token, "/read-all", null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
        }
        assertThat(readAt(id)).isNull();
    }

    @Test
    void mustChangePasswordBlocksAllFourWithBrTk17AndWritesNothing() {
        long staff = account("RECEPTIONIST");
        String token = token(staff);
        long id = insert(staff, "T", "a", "b", null, null, at(1));
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", staff);

        for (ResponseEntity<Map<String, Object>> response : List.of(get(token, ""), get(token, "/unread-count", ""),
                post(token, "/" + id + "/read", null, null), post(token, "/read-all", null, null))) {
            assertError(response, HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", null);
            assertThat((String) response.getBody().get("message")).endsWith("(BR-TK-17)");
        }
        assertThat(readAt(id)).isNull();
    }

    // ---------------------------------------------------------------- hình thức request

    @Test
    void wrongParameterTypesAre400MalformedRequest() {
        assertError(get(myToken, "unreadOnly=abc"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'unreadOnly' sai kiểu dữ liệu");
        assertError(get(myToken, "page=x"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'page' sai kiểu dữ liệu");
        assertError(get(myToken, "size=1.5"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'size' sai kiểu dữ liệu");
        assertError(post(myToken, "/abc/read", null, null), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Tham số 'notificationId' sai kiểu dữ liệu");
        assertError(post(myToken, "/99999999999999999999/read", null, null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST", "Tham số 'notificationId' sai kiểu dữ liệu");
    }

    @Test
    void otherMethodsAre405() {
        long id = insert(me, "T", "a", "b", null, null, at(1));

        assertError(exchange(HttpMethod.PUT, myToken, ""), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.DELETE, myToken, "/" + id + "/read"), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.GET, myToken, "/read-all"), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertError(exchange(HttpMethod.POST, myToken, "/unread-count"), HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED", null);
        assertThat(readAt(id)).isNull();
    }

    @Test
    void nothingIsAudited() {
        long id = insert(me, "T", "a", "b", null, null, at(1));
        long before = maxAuditId();

        get(myToken, "");
        get(myToken, "/unread-count", "");
        markRead(myToken, id);
        post(myToken, "/read-all", null, null);
        post(myToken, "/999999999/read", null, null);

        assertThat(maxAuditId()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- đồng thời

    /**
     * Một transaction khác đang đánh dấu cùng dòng (giữ khóa, chưa commit). Request #7 / #8 với giờ khác phải chờ khóa,
     * rồi đọc lại điều kiện {@code read_at IS NULL} sau khi bên kia commit: không ghi đè {@code read_at}, vẫn 204.
     */
    @Test
    void markReadAndReadAllWaitingOnAConcurrentMarkKeepTheFirstReadAt() throws Exception {
        long one = insert(me, "T", "a", "b", null, null, at(1));
        long two = insert(me, "T", "a", "b", null, null, at(2));
        Instant first = T0.plusSeconds(10);
        clock.set(T0.plusSeconds(99));

        assertThat(whileMarkedElsewhere(List.of(one), first, () -> markRead(myToken, one)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(one)).isEqualTo(first);

        ResponseEntity<?> all = whileMarkedElsewhere(List.of(two), first, () -> post(myToken, "/read-all", null, null));
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(readAt(two)).isEqualTo(first);
    }

    @Test
    void twoConcurrentReadAllsBothSucceed() throws Exception {
        for (int i = 0; i < 30; i++) {
            insert(me, "T", "a", "b", null, null, at(i));
        }
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> results = List.of(
                    pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return post(myToken, "/read-all", null, null);
                    }),
                    pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return post(myToken, "/read-all", null, null);
                    }));
            for (Future<ResponseEntity<Map<String, Object>>> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok(get(myToken, "/unread-count", ""))).containsEntry("count", 0);
    }

    // ---------------------------------------------------------------- index

    /**
     * Câu SQL thật của repository (đọc từ {@code @Query}) dùng index V1 {@code ix_notifications_account_read_created}
     * khi bảng có 6 000 dòng của 300 tài khoản khác. Dữ liệu chèn và {@code ANALYZE} trong một transaction rollback.
     * Không khẳng định bỏ được bước Sort: plan đo 14/10 có Sort ở cả hai câu trang ({@code read_at IS NULL} không cho
     * thứ tự, index không có {@code id}) — sort trên các dòng của một tài khoản, nợ D022.
     */
    @Test
    void repositoryQueriesUseTheAccountIndex() {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO accounts (email, password_hash, role, status)
                    SELECT 'explain-' || ? || '-' || g || '@petcare.test', 'hash', 'CUSTOMER', 'ACTIVE'
                    FROM generate_series(1, 300) g
                    """, tag);
            jdbc.update("""
                    INSERT INTO notifications (account_id, type, title, body, read_at, created_at)
                    SELECT a.id, 'EXPLAIN', 't', 'b', CASE WHEN g % 3 = 0 THEN now() END,
                           now() - g * interval '1 minute'
                    FROM generate_series(1, 20) g
                    CROSS JOIN (SELECT id FROM accounts WHERE email LIKE ?) a
                    """, "explain-" + tag + "-%");
            for (int i = 0; i < 50; i++) {
                insert(me, "MINE", "t", "b", null, i % 2 == 0 ? SEEDED_AT : null, at(i));
            }
            jdbc.execute("ANALYZE notifications");

            for (String method : List.of("findMine", "findMineUnread")) {
                Query query = query(method);
                assertUsesIndex(method, query.value() + " LIMIT 20 OFFSET 0", me);
                assertUsesIndex(method + " count", query.countQuery(), me);
            }
            assertUsesIndex("countUnread", query("countUnread").value(), me);
            // :now xuất hiện hai lần (read_at, updated_at) → hai tham số bind
            assertUsesIndex("markAllRead", query("markAllRead").value().replace(":now", "?"), Timestamp.from(T0),
                    Timestamp.from(T0), me);
            String markRead = explain(query("markRead").value().replace(":now", "?").replace(":id", "?")
                    .replace(":accountId", "?"), Timestamp.from(T0), Timestamp.from(T0), 1L, me);
            assertThat(markRead).as(markRead).contains("notifications_pkey").doesNotContain("Seq Scan");

            status.setRollbackOnly();
        });
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<?> whileMarkedElsewhere(List<Long> ids, Instant readAt,
            java.util.concurrent.Callable<ResponseEntity<?>> request) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement update = holder.prepareStatement(
                    "UPDATE notifications SET read_at = ?, updated_at = ? WHERE id = ANY (?)")) {
                update.setTimestamp(1, Timestamp.from(readAt));
                update.setTimestamp(2, Timestamp.from(readAt));
                update.setArray(3, holder.createArrayOf("bigint", ids.toArray()));
                update.executeUpdate();
            }
            CompletableFuture<ResponseEntity<?>> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return request.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            awaitBlockedUpdate();
            assertThat(pending).isNotDone();
            holder.commit();
            return pending.get(20, TimeUnit.SECONDS);
        }
    }

    private void awaitBlockedUpdate() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%update notifications%'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Request không chờ khóa dòng notifications");
    }

    private static Query query(String method) {
        return java.util.Arrays.stream(NotificationRepository.class.getMethods())
                .filter(m -> m.getName().equals(method)).findFirst().orElseThrow().getAnnotation(Query.class);
    }

    private void assertUsesIndex(String label, String sql, Object... params) {
        String plan = explain(sql.replace(":accountId", "?"), params);
        assertThat(plan).as("%s\n%s", label, plan).contains("ix_notifications_account_read_created")
                .doesNotContain("Seq Scan");
    }

    private String explain(String sql, Object... params) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, params));
    }

    private long account(String role) {
        String email = "my-notif-it-" + tag + "-" + SEQ.incrementAndGet() + "@petcare.test";
        if ("CUSTOMER".equals(role)) {
            return jdbc.queryForObject("""
                    INSERT INTO accounts (email, password_hash, role, status)
                    VALUES (?, 'hash', 'CUSTOMER', 'ACTIVE') RETURNING id
                    """, Long.class, email);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', ?, 'ACTIVE') RETURNING id
                """, Long.class, email, role);
        boolean branchScoped = !"ADMIN".equals(role) && !"SUPER_MANAGER".equals(role);
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, ?, ?)", id,
                role + " " + tag, branchScoped ? branch : null);
        return id;
    }

    private String token(long accountId) {
        return tx.execute(status -> sessions.open(accountId, "203.0.113.9", "MyNotificationIT")).accessToken();
    }

    private long insert(long accountId, String type, String title, String body, String linkUrl, Instant readAt,
            Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (account_id, type, title, body, link_url, read_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, accountId, type, title, body, linkUrl, readAt == null ? null : Timestamp.from(readAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private static Instant at(int minutes) {
        return SEEDED_AT.plus(Duration.ofMinutes(minutes));
    }

    private Instant readAt(long id) {
        Timestamp value = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private Instant updatedAt(long id) {
        return jdbc.queryForObject("SELECT updated_at FROM notifications WHERE id = ?", Timestamp.class, id)
                .toInstant();
    }

    private long maxAuditId() {
        return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM audit_logs", Long.class);
    }

    private ResponseEntity<Map<String, Object>> get(String token, String rawQuery) {
        return get(token, "", rawQuery);
    }

    private ResponseEntity<Map<String, Object>> get(String token, String suffix, String rawQuery) {
        return http.exchange(uri(suffix, rawQuery), HttpMethod.GET, new HttpEntity<>(bearer(token)), MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String token, String suffix, String body, MediaType type) {
        HttpHeaders headers = bearer(token);
        if (type != null) {
            headers.setContentType(type);
        }
        return http.exchange(uri(suffix, ""), HttpMethod.POST, new HttpEntity<>(body, headers), MAP);
    }

    private ResponseEntity<String> markRead(String token, long id) {
        return http.exchange(uri("/" + id + "/read", ""), HttpMethod.POST, new HttpEntity<>(bearer(token)),
                String.class);
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String token, String suffix) {
        return http.exchange(uri(suffix, ""), method, new HttpEntity<>(bearer(token)), MAP);
    }

    private URI uri(String suffix, String rawQuery) {
        return URI.create(http.getRootUri() + PATH + suffix + (rawQuery.isEmpty() ? "" : "?" + rawQuery));
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
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("content");
    }

    private static List<Long> ids(Map<String, Object> data) {
        return items(data).stream().map(item -> ((Number) item.get("notificationId")).longValue()).toList();
    }

    private static void assertError(ResponseEntity<Map<String, Object>> response, HttpStatus status,
            String errorCode, String message) {
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
