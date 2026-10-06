package com.petcare.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

/**
 * Cấu hình security (docs/adr/0003): endpoint cần đăng nhập bị chặn với {@code ErrorResponse} chuẩn (do
 * GlobalExceptionHandler tạo), path public mở, CORS theo {@code app.cors.allowed-origins}. TraceIdFilter và
 * GlobalExceptionHandler được slice {@code @WebMvcTest} tự nạp (Filter, ControllerAdvice). Việc kiểm tra phiên thật
 * nằm ở {@code AuthenticationIT}; ở đây {@link SessionAuthenticator} là mock.
 */
@WebMvcTest(controllers = SecurityConfigTest.PingController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, CorsConfig.class,
        JwtTokenService.class, TimeConfig.class, SecurityConfigTest.PingController.class})
class SecurityConfigTest {

    @RestController
    static class PingController {

        @GetMapping("/test/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/api/public/test/ping")
        String publicPing() {
            return "pong";
        }
    }

    record Principal(Long accountId, String email, Long sessionId, String role, Long branchId,
            AccessScope accessScope, boolean mustChangePassword) implements SecurityPrincipal {
    }

    @MockitoBean
    private SessionAuthenticator sessionAuthenticator;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousRequestGets401ErrorResponseWithTraceId() throws Exception {
        mvc.perform(get("/test/ping").header(TraceContext.HEADER, "demo-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(TraceContext.HEADER, "demo-1"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.traceId").value("demo-1"));
    }

    @Test
    @WithMockUser
    void authenticatedRequestPasses() throws Exception {
        mvc.perform(get("/test/ping")).andExpect(status().isOk());
    }

    @Test
    void invalidBearerTokenGets401WithoutTouchingSessions() throws Exception {
        mvc.perform(get("/test/ping").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        verify(sessionAuthenticator, never()).authenticate(any());
    }

    @Test
    void publicPathOpenWithoutTokenAndWithGarbageToken() throws Exception {
        mvc.perform(get("/api/public/test/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/public/test/ping").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isOk());
    }

    /**
     * docs/adr/0005, docs/api/00-method.md §3.1: path public của contract (cùng matcher cho permitAll và filter).
     * Dòng {@code false}: path phải đăng nhập, kể cả path gần giống tiền tố public.
     */
    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/login,               true",
            "POST, /api/auth/register,            true",
            "POST, /api/auth/register/verify,     true",
            "POST, /api/auth/register/resend-otp, true",
            "POST, /api/auth/password/forgot,     true",
            "POST, /api/auth/password/reset,      true",
            "GET,  /api/public/vets,              true",
            "GET,  /api/public/branches/7,        true",
            "GET,  /actuator/health,              true",
            "GET,  /v3/api-docs,                  true",
            "POST, /api/auth/logout,              false",
            "GET,  /api/me,                       false",
            "POST, /api/me/password,              false",
            "GET,  /api/staff,                    false",
            "GET,  /api/publicx,                  false",
            "POST, /api/auth/registerx,           false",
            "POST, /api/auth/loginx,              false",
            "GET,  /actuator/env,                 false"})
    void publicRequestMatcherCoversContractPublicPaths(String method, String path, boolean expected) {
        assertThat(SecurityConfig.PUBLIC_REQUESTS.matches(new MockHttpServletRequest(method, path)))
                .isEqualTo(expected);
    }

    /** docs/adr/0005: token còn hạn gửi tới path public bị bỏ qua (không tra phiên); path thường vẫn tra phiên. */
    @Test
    void publicPathWithValidTokenSkipsSessionLookup() throws Exception {
        Instant now = Instant.now();
        String token = tokens.issue(5L, 9L, "jti-public", now, now.plus(Duration.ofHours(1)));
        when(sessionAuthenticator.authenticate(any())).thenReturn(Optional.of(new Principal(5L, "vet@petcare.test",
                9L, "VET", 3L, AccessScope.BRANCH, false)));

        mvc.perform(get("/api/public/test/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        verify(sessionAuthenticator, never()).authenticate(any());

        mvc.perform(get("/test/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        verify(sessionAuthenticator, times(1)).authenticate(any());
    }

    @Test
    void preflightFromAllowedOriginGetsCorsHeaders() throws Exception {
        mvc.perform(options("/test/ping")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/test/ping")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
