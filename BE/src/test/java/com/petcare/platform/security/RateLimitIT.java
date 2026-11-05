package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockingDetails;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;

/**
 * Giới hạn tần suất theo IP của endpoint public identity trên Postgres 17 + Redis 7 thật, qua HTTP (docs/adr/0100,
 * nợ D011). Ngưỡng nhỏ (login 3, register 2, password 2 mỗi 60 s). Mỗi test dùng một IP riêng gửi bằng
 * {@code X-Forwarded-For} từ loopback (proxy nội bộ được tin, docs/adr/0002), và Redis được xóa trước mỗi test.
 * Chứng minh: G1 chặn trước BCrypt và audit · G2 IP khác không bị ảnh hưởng · G3 khóa có TTL, hết cửa sổ thì cho
 * qua lại · G6 envelope 429 + Retry-After + CORS · G7 nhóm độc lập, path / method ngoài phạm vi không đếm.
 * Chatbot AI (docs/adr/0110, BR-AI-09; ngưỡng nhỏ 3 / phút và 5 / giờ): G8 hai đường dẫn dùng chung một bộ đếm theo IP ·
 * G9 cửa sổ giờ chặn dù cửa sổ phút đã mở lại · G10 request bị chặn bởi cửa sổ phút không tiêu hao hạn mức giờ.
 * Redis chết (G5) ở {@code RateLimitRedisDownIT}; đếm atomic ở {@code RedisRateLimiterIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RateLimitIT.RedisContainer.class})
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login.limit=3",
        "app.rate-limit.login.window-seconds=60",
        "app.rate-limit.register.limit=2",
        "app.rate-limit.register.window-seconds=60",
        "app.rate-limit.password.limit=2",
        "app.rate-limit.password.window-seconds=60",
        "app.rate-limit.ai.limit=3",
        "app.rate-limit.ai.window-seconds=60",
        "app.rate-limit.ai-hour.limit=5",
        "app.rate-limit.ai-hour.window-seconds=3600"})
class RateLimitIT {

    /** Redis thật cho test này; {@code @ServiceConnection} ghi đè {@code spring.data.redis.*} của profile test. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainer {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        }
    }

    private record Reply(int status, java.net.http.HttpHeaders headers, String body) {

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String ORIGIN = "http://localhost:5173";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void flushRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) (RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    /** Địa chỉ công cộng (dải TEST-NET) để không bị {@code RemoteIpValve} coi là proxy nội bộ. */
    private static String nextIp() {
        int n = SEQ.incrementAndGet();
        return "198.51.100." + (n % 250 + 1);
    }

    private Reply send(String method, String path, String ip, Object body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (ip != null) {
            request.header("X-Forwarded-For", ip);
        }
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers(), response.body());
    }

    private Reply login(String ip) throws Exception {
        return send("POST", "/api/auth/login", ip,
                Map.of("email", "rate-" + SEQ.incrementAndGet() + "@example.com", "password", "matkhau123"), Map.of());
    }

    /** Endpoint chatbot chưa có controller (T77): dưới ngưỡng không phải 429; vượt ngưỡng thì filter trả 429 trước mọi thứ. */
    private Reply aiChat(String path, String ip) throws Exception {
        return send("POST", path, ip, Map.of("question", "Chó mấy tuần tuổi thì tiêm dại?"), Map.of());
    }

    private int countLoginFailed() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED'", Integer.class);
    }

    private int encoderCalls() {
        return mockingDetails(passwordEncoder).getInvocations().size();
    }

    @Test
    void overTheLimitIsRejectedBeforeBcryptAndAuditWithTheStandardEnvelope() throws Exception {
        String ip = nextIp();
        int encoderBefore = encoderCalls();
        int auditBefore = countLoginFailed();

        for (int i = 0; i < 3; i++) {
            assertThat(login(ip).status()).as("lần %d dưới ngưỡng", i + 1).isEqualTo(401);
        }
        int encoderAfterAllowed = encoderCalls();
        int auditAfterAllowed = countLoginFailed();
        assertThat(encoderAfterAllowed).as("request được phép có chạy BCrypt").isGreaterThan(encoderBefore);
        assertThat(auditAfterAllowed).as("request được phép có ghi audit").isGreaterThan(auditBefore);

        Reply denied = login(ip);

        assertThat(denied.status()).isEqualTo(429);
        JsonNode body = json.readTree(denied.body());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("errorCode").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(body.get("statusCode").asInt()).isEqualTo(429);
        assertThat(body.get("message").asText()).contains("Bạn thao tác quá nhanh");
        assertThat(body.get("timestamp").asText()).isNotBlank();
        assertThat(body.get("traceId").asText()).isEqualTo(denied.header("X-Trace-Id"));
        assertThat(Integer.parseInt(denied.header("Retry-After"))).isBetween(1, 60);
        assertThat(encoderCalls()).as("bị chặn nên không chạy BCrypt").isEqualTo(encoderAfterAllowed);
        assertThat(countLoginFailed()).as("bị chặn nên không ghi audit").isEqualTo(auditAfterAllowed);
    }

    @Test
    void anotherIpIsNotAffected() throws Exception {
        String exhausted = nextIp();
        String other = nextIp();
        for (int i = 0; i < 4; i++) {
            login(exhausted);
        }
        assertThat(login(exhausted).status()).isEqualTo(429);

        assertThat(login(other).status()).isEqualTo(401);
    }

    @Test
    void groupsAreIndependentAndEachHasItsOwnThreshold() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            login(ip);
        }
        assertThat(login(ip).status()).as("nhóm login đã cạn").isEqualTo(429);

        Map<String, String> none = Map.of();
        Map<String, String> otp = Map.of("email", "nobody-" + SEQ.incrementAndGet() + "@example.com");
        assertThat(send("POST", "/api/auth/register/resend-otp", ip, otp, none).status()).isEqualTo(404);
        assertThat(send("POST", "/api/auth/register/resend-otp", ip, otp, none).status()).isEqualTo(404);
        assertThat(send("POST", "/api/auth/register/resend-otp", ip, otp, none).status()).isEqualTo(429);

        assertThat(send("POST", "/api/auth/password/forgot", ip, otp, none).status()).isEqualTo(202);
        assertThat(send("POST", "/api/auth/password/forgot", ip, otp, none).status()).isEqualTo(202);
        assertThat(send("POST", "/api/auth/password/forgot", ip, otp, none).status()).isEqualTo(429);
    }

    @Test
    void registerVerifyAndResendShareTheRegisterGroup() throws Exception {
        String ip = nextIp();
        Map<String, String> none = Map.of();

        assertThat(send("POST", "/api/auth/register/resend-otp", ip,
                Map.of("email", "a-" + SEQ.incrementAndGet() + "@example.com"), none).status()).isNotEqualTo(429);
        assertThat(send("POST", "/api/auth/register/verify", ip,
                Map.of("email", "b-" + SEQ.incrementAndGet() + "@example.com", "otp", "000000"), none).status())
                .isNotEqualTo(429);

        assertThat(send("POST", "/api/auth/register", ip, Map.of(), none).status())
                .as("lần thứ 3 của nhóm register, dù path khác").isEqualTo(429);
    }

    @Test
    void otherPathsAndMethodsAreNeverCounted() throws Exception {
        String ip = nextIp();
        Map<String, String> none = Map.of();

        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", "/api/auth/refresh", ip, Map.of("refreshToken", "x"), none).status())
                    .isEqualTo(401);
            assertThat(send("GET", "/api/auth/login", ip, null, none).status()).isEqualTo(405);
            assertThat(send("GET", "/api/public/branches", ip, null, none).status()).isNotEqualTo(429);
        }

        assertThat(redis.keys("rl:*:" + ip)).isEmpty();
    }

    @Test
    void aiChatEndpointsShareOneCounterPerIp() throws Exception {
        String ip = nextIp();

        assertThat(aiChat("/api/public/ai/chat", ip).status()).isNotEqualTo(429);
        assertThat(aiChat("/api/me/ai/chat", ip).status()).as("filter chạy trước JWT: chưa có token thì 401").isEqualTo(401);
        assertThat(aiChat("/api/public/ai/chat", ip).status()).isNotEqualTo(429);

        Reply denied = aiChat("/api/me/ai/chat", ip);
        assertThat(denied.status()).as("lần 4 của cùng IP dù khác đường dẫn").isEqualTo(429);
        assertThat(json.readTree(denied.body()).get("errorCode").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(Integer.parseInt(denied.header("Retry-After"))).as("Retry-After của cửa sổ phút").isBetween(1, 60);
        assertThat(aiChat("/api/public/ai/chat", ip).status()).isEqualTo(429);

        assertThat(aiChat("/api/public/ai/chat", nextIp()).status()).as("IP khác không bị ảnh hưởng").isNotEqualTo(429);
        assertThat(login(ip).status()).as("nhóm login độc lập với nhóm AI").isEqualTo(401);
    }

    @Test
    void aiHourWindowBlocksEvenWhenTheMinuteWindowHasReset() throws Exception {
        String ip = nextIp();
        String minuteKey = "rl:ai:" + ip;
        for (int i = 0; i < 3; i++) {
            assertThat(aiChat("/api/public/ai/chat", ip).status()).as("lần %d", i + 1).isNotEqualTo(429);
        }
        assertThat(redis.getExpire(minuteKey)).as("cửa sổ phút").isBetween(1L, 60L);
        assertThat(redis.getExpire("rl:ai-hour:" + ip)).as("cửa sổ giờ dài hơn cửa sổ phút").isBetween(61L, 3600L);

        redis.expire(minuteKey, Duration.ofMillis(50));
        await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(minuteKey));

        for (int i = 0; i < 2; i++) {
            assertThat(aiChat("/api/public/ai/chat", ip).status()).as("mở lại cửa sổ phút, giờ mới 4–5/5").isNotEqualTo(429);
        }
        Reply denied = aiChat("/api/me/ai/chat", ip);
        assertThat(denied.status()).as("lần 6 trong giờ: phút mới 3/3 nhưng giờ đã vượt 5").isEqualTo(429);
        assertThat(Integer.parseInt(denied.header("Retry-After"))).as("Retry-After của cửa sổ giờ")
                .isBetween(61, 3600);
    }

    @Test
    void aiMinuteDenialDoesNotSpendTheHourBudget() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            assertThat(aiChat("/api/public/ai/chat", ip).status()).isNotEqualTo(429);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(aiChat("/api/public/ai/chat", ip).status()).as("bị cửa sổ phút chặn").isEqualTo(429);
        }

        assertThat(redis.opsForValue().get("rl:ai-hour:" + ip)).as("chỉ 3 request qua cửa sổ phút mới tính giờ")
                .isEqualTo("3");
    }

    @Test
    void keyHasATtlAndTheWindowResets() throws Exception {
        String ip = nextIp();
        String key = "rl:login:" + ip;
        for (int i = 0; i < 3; i++) {
            login(ip);
        }
        Long ttl = redis.getExpire(key);
        assertThat(ttl).as("khóa đếm phải có TTL, không được bất tử").isBetween(1L, 60L);
        assertThat(login(ip).status()).isEqualTo(429);

        redis.expire(key, Duration.ofMillis(50));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(login(ip).status()).as("sang cửa sổ mới").isEqualTo(401));
    }

    @Test
    void deniedResponseStillCarriesCorsHeadersAndPreflightIsNotCounted() throws Exception {
        String ip = nextIp();
        Map<String, String> cors = Map.of("Origin", ORIGIN);
        for (int i = 0; i < 3; i++) {
            send("POST", "/api/auth/login", ip, Map.of("email", "c@example.com", "password", "x"), cors);
        }

        Reply denied = send("POST", "/api/auth/login", ip, Map.of("email", "c@example.com", "password", "x"), cors);

        assertThat(denied.status()).isEqualTo(429);
        assertThat(denied.header("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(denied.header("Access-Control-Expose-Headers")).contains("Retry-After");

        Map<String, String> preflight = new LinkedHashMap<>();
        preflight.put("Origin", ORIGIN);
        preflight.put("Access-Control-Request-Method", "POST");
        preflight.put("Access-Control-Request-Headers", "content-type");
        Reply options = send("OPTIONS", "/api/auth/login", ip, null, preflight);
        assertThat(options.status()).as("preflight không bị chặn dù IP đã cạn").isEqualTo(200);
        assertThat(options.header("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
    }
}
