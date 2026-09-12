package com.petcare.platform.security;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtTokenProvider tokenProvider;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(SECRET, 15, 30);
        principal = UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .phone("0900000000")
                .name("Nguyen Van A")
                .role(UserRole.CUSTOMER)
                .scope(SecurityScope.CUSTOMER)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(UUID.randomUUID())
                .storeId(null)
                .build();
    }

    @Test
    void accessToken_roundTrip_preservesAllClaims() {
        String token = tokenProvider.generateAccessToken(principal);

        assertThat(tokenProvider.validateToken(token)).isTrue();
        assertThat(tokenProvider.getTokenType(token)).isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS);
        assertThat(tokenProvider.getJti(token)).isNotNull();

        UserPrincipal parsed = tokenProvider.parseToken(token);
        assertThat(parsed.getUserId()).isEqualTo(principal.getUserId());
        assertThat(parsed.getAccountId()).isEqualTo(principal.getAccountId());
        assertThat(parsed.getPhone()).isEqualTo(principal.getPhone());
        assertThat(parsed.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(parsed.getScope()).isEqualTo(SecurityScope.CUSTOMER);
        assertThat(parsed.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(parsed.getOrganizationId()).isEqualTo(principal.getOrganizationId());
        assertThat(parsed.getStoreId()).isNull();
    }

    @Test
    void refreshToken_hasRefreshType_andNoBusinessClaims() {
        String token = tokenProvider.generateRefreshToken(principal);

        assertThat(tokenProvider.validateToken(token)).isTrue();
        assertThat(tokenProvider.getTokenType(token)).isEqualTo(JwtTokenProvider.TOKEN_TYPE_REFRESH);

        UserPrincipal parsed = tokenProvider.parseToken(token);
        assertThat(parsed.getUserId()).isEqualTo(principal.getUserId());
        assertThat(parsed.getRole()).isNull();
    }

    @Test
    void twoTokensForSamePrincipal_haveDifferentJti() {
        String tokenA = tokenProvider.generateAccessToken(principal);
        String tokenB = tokenProvider.generateAccessToken(principal);

        assertThat(tokenProvider.getJti(tokenA)).isNotEqualTo(tokenProvider.getJti(tokenB));
    }

    @Test
    void expiredToken_failsValidation() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1, 30);
        String token = expiredProvider.generateAccessToken(principal);

        assertThat(expiredProvider.validateToken(token)).isFalse();
        assertThatThrownBy(() -> expiredProvider.getTokenType(token)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void tamperedSignature_failsValidation() {
        String token = tokenProvider.generateAccessToken(principal);
        JwtTokenProvider otherKeyProvider = new JwtTokenProvider(SECRET + "-different", 15, 30);

        assertThat(otherKeyProvider.validateToken(token)).isFalse();
    }
}
