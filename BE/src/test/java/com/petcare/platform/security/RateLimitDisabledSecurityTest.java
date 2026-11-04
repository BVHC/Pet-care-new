package com.petcare.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.TimeConfig;

/**
 * G8 của docs/adr/0100: không có bean {@link RateLimiter} (tắt {@code app.rate-limit.enabled}, như profile test) thì
 * {@link SecurityConfig} không gắn filter và mọi request đi qua dù ngưỡng chỉ là 1.
 */
@WebMvcTest(controllers = RateLimitDisabledSecurityTest.AuthProbe.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, CorsConfig.class,
        JwtTokenService.class, TimeConfig.class, RateLimitDisabledSecurityTest.AuthProbe.class})
@TestPropertySource(properties = {"app.rate-limit.enabled=false", "app.rate-limit.login.limit=1"})
class RateLimitDisabledSecurityTest {

    @RestController
    static class AuthProbe {

        @PostMapping("/api/auth/login")
        String ok() {
            return "ok";
        }
    }

    @MockitoBean
    private SessionAuthenticator sessionAuthenticator;

    @Autowired
    private MockMvc mvc;

    @Test
    void noLimiterBeanMeansNoFilterAndNoThrottling() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        }
    }
}
