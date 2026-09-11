package com.petcare.platform.security;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.security.token.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private UserPrincipal principal() {
        return UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .phone("0911111111")
                .name("Test User")
                .role(UserRole.CUSTOMER)
                .scope(SecurityScope.CUSTOMER)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    void validAccessToken_isAccepted() throws Exception {
        String token = tokenProvider.generateAccessToken(principal());

        mockMvc.perform(get("/api/test/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("pong:0911111111"));
    }

    @Test
    void missingToken_isRejectedWithUnauthorizedReason() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void expiredToken_isRejectedWithSpecificReason() throws Exception {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(jwtSecret, -1, 30);
        String token = expiredProvider.generateAccessToken(principal());

        mockMvc.perform(get("/api/test/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    void refreshTokenUsedAsAccessToken_isRejected() throws Exception {
        String refreshToken = tokenProvider.generateRefreshToken(principal());

        mockMvc.perform(get("/api/test/ping").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN_TYPE"));
    }

    @Test
    void blacklistedAccessToken_isRejected() throws Exception {
        String token = tokenProvider.generateAccessToken(principal());
        UUID jti = tokenProvider.getJti(token);
        tokenBlacklistService.blacklist(jti, tokenProvider.getExpiresAt(token), "LOGOUT");

        mockMvc.perform(get("/api/test/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_REVOKED"));
    }
}
