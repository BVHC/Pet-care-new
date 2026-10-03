package com.petcare.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.TraceContext;

/**
 * Cấu hình security tạm: endpoint chưa mở bị chặn với {@code ErrorResponse} chuẩn (do GlobalExceptionHandler tạo),
 * CORS theo {@code app.cors.allowed-origins}. TraceIdFilter và GlobalExceptionHandler được slice
 * {@code @WebMvcTest} tự nạp (Filter, ControllerAdvice).
 */
@WebMvcTest(controllers = SecurityConfigTest.PingController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, CorsConfig.class,
        SecurityConfigTest.PingController.class})
class SecurityConfigTest {

    @RestController
    static class PingController {

        @GetMapping("/test/ping")
        String ping() {
            return "pong";
        }
    }

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
