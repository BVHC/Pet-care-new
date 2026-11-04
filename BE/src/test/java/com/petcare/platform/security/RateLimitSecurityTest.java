package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

/**
 * Nối dây của giới hạn tần suất trong {@link SecurityConfig} (docs/adr/0100): có bean {@link RateLimiter} thì filter
 * được gắn sau {@code CorsFilter}, nên 429 vẫn mang header CORS và {@code Retry-After}, preflight không bị đếm, path
 * ngoài phạm vi không bị chặn. Bộ đếm là bản trong bộ nhớ; đếm Redis thật nằm ở {@code RateLimitIT}.
 */
@WebMvcTest(controllers = RateLimitSecurityTest.AuthProbe.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, CorsConfig.class,
        JwtTokenService.class, TimeConfig.class, RateLimitSecurityTest.AuthProbe.class,
        RateLimitSecurityTest.InMemoryLimiter.class})
@TestPropertySource(properties = "app.rate-limit.login.limit=2")
class RateLimitSecurityTest {

    private static final String ORIGIN = "http://localhost:5173";

    @RestController
    static class AuthProbe {

        @PostMapping({"/api/auth/login", "/api/auth/password/forgot", "/api/auth/refresh"})
        String ok() {
            return "ok";
        }
    }

    /** Đếm theo khóa trong bộ nhớ; bị từ chối thì Retry-After cố định 42. */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryLimiter {

        static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

        @Bean
        RateLimiter rateLimiter() {
            return (key, limit, window) -> COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() <= limit
                    ? RateLimiter.Decision.ALLOWED
                    : RateLimiter.Decision.denied(42);
        }
    }

    @MockitoBean
    private SessionAuthenticator sessionAuthenticator;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetCounters() {
        InMemoryLimiter.COUNTS.clear();
    }

    private void postLogin(String remoteAddr) throws Exception {
        mvc.perform(post("/api/auth/login").with(request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        }));
    }

    @Test
    void overTheLimitGets429WithEnvelopeTraceIdAndRetryAfter() throws Exception {
        postLogin("203.0.113.5");
        postLogin("203.0.113.5");

        mvc.perform(post("/api/auth/login").header(TraceContext.HEADER, "demo-429").with(request -> {
                    request.setRemoteAddr("203.0.113.5");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(header().string(TraceContext.HEADER, "demo-429"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.statusCode").value(429))
                .andExpect(jsonPath("$.traceId").value("demo-429"))
                .andExpect(jsonPath("$.message", containsString("42 giây")));
    }

    @Test
    void deniedResponseCarriesCorsHeadersBecauseTheFilterRunsAfterCorsFilter() throws Exception {
        postLogin("203.0.113.6");
        postLogin("203.0.113.6");

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).with(request -> {
                    request.setRemoteAddr("203.0.113.6");
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void preflightIsAnsweredByCorsAndNeverCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(options("/api/auth/login").header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", "POST").with(request -> {
                                request.setRemoteAddr("203.0.113.7");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }

        assertThat(InMemoryLimiter.COUNTS).isEmpty();
    }

    @Test
    void exhaustedLoginDoesNotBlockOtherGroupsOrPaths() throws Exception {
        for (int i = 0; i < 3; i++) {
            postLogin("203.0.113.8");
        }
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/auth/refresh").with(request -> {
                request.setRemoteAddr("203.0.113.8");
                return request;
            })).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/password/forgot").with(request -> {
            request.setRemoteAddr("203.0.113.8");
            return request;
        })).andExpect(status().isOk());

        assertThat(InMemoryLimiter.COUNTS.keySet()).containsExactlyInAnyOrder(
                "rl:login:203.0.113.8", "rl:password:203.0.113.8");
    }

    /** Giá trị mặc định của {@code application.yml} (docs/adr/0100) và việc ghi đè bằng thuộc tính test. */
    @Test
    void propertiesBindFromApplicationYmlWithTestOverride(@Autowired RateLimitProperties properties) {
        assertThat(properties.login()).isEqualTo(new RateLimitProperties.Rule(2, 60));
        assertThat(properties.register()).isEqualTo(new RateLimitProperties.Rule(10, 60));
        assertThat(properties.password()).isEqualTo(new RateLimitProperties.Rule(10, 60));
        assertThat(properties.breakerSeconds()).isEqualTo(10);
    }
}
