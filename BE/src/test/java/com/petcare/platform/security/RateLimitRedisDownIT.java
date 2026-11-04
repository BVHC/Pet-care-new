package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.petcare.TestcontainersConfiguration;

/**
 * G5 của docs/adr/0100: Redis không với tới được thì endpoint đăng nhập vẫn chạy bình thường (401 cho mật khẩu sai,
 * không 5xx, không 429) và circuit-breaker chỉ báo lỗi một lần thay vì mỗi request. Không có container Redis: cổng 1
 * từ chối kết nối ngay, đúng như Redis chết thật, đi qua Lettuce và cấu hình Spring thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login.limit=1",
        "app.rate-limit.breaker-seconds=30",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=1"})
class RateLimitRedisDownIT {

    private static final Pattern UNAVAILABLE = Pattern.compile("RATE_LIMIT_UNAVAILABLE");

    @LocalServerPort
    private int port;

    @Test
    void loginStaysAvailableAndTheBreakerReportsOnlyOnce(CapturedOutput output) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 5; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "198.51.100.77")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"redis-down-" + i + "@example.com\",\"password\":\"matkhau123\"}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as("lần %d: giới hạn 1/60 s nhưng Redis chết nên không áp dụng", i + 1)
                    .isEqualTo(401);
        }

        Matcher matcher = UNAVAILABLE.matcher(output.getAll());
        int reports = 0;
        while (matcher.find()) {
            reports++;
        }
        assertThat(reports).as("breaker mở 30 s: chỉ log lần lỗi đầu").isEqualTo(1);
    }
}
