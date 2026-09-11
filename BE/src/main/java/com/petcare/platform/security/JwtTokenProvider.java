package com.petcare.platform.security;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTokenTtlMs;
    private final long refreshTokenTtlMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-ttl-min:60}") long accessTokenTtlMin,
            @Value("${jwt.refresh-token-ttl-days:30}") long refreshTokenTtlDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMs = accessTokenTtlMin * 60 * 1000;
        this.refreshTokenTtlMs = refreshTokenTtlDays * 24 * 60 * 60 * 1000;
    }

    public long getAccessTokenTtlMs() {
        return accessTokenTtlMs;
    }

    public String generateAccessToken(UserPrincipal user) {
        return generateAccessToken(user, UUID.randomUUID());
    }

    public String generateAccessToken(UserPrincipal user, UUID jti) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlMs);

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .id(jti.toString())
                .claim("type", TOKEN_TYPE_ACCESS)
                .claim("accountId", toClaim(user.getAccountId()))
                .claim("phone", user.getPhone())
                .claim("name", user.getName())
                .claim("role", user.getRole() != null ? user.getRole().name() : null)
                .claim("scope", user.getScope() != null ? user.getScope().name() : null)
                .claim("accountStatus", user.getAccountStatus() != null ? user.getAccountStatus().name() : null)
                .claim("organizationId", toClaim(user.getOrganizationId()))
                .claim("storeId", toClaim(user.getStoreId()))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(UserPrincipal user) {
        return generateRefreshToken(user, UUID.randomUUID());
    }

    public String generateRefreshToken(UserPrincipal user, UUID jti) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenTtlMs);

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .id(jti.toString())
                .claim("type", TOKEN_TYPE_REFRESH)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public UserPrincipal parseToken(String token) {
        Claims claims = parseClaims(token);

        String accountId = claims.get("accountId", String.class);
        String role = claims.get("role", String.class);
        String scope = claims.get("scope", String.class);
        String accountStatus = claims.get("accountStatus", String.class);
        String organizationId = claims.get("organizationId", String.class);
        String storeId = claims.get("storeId", String.class);

        return UserPrincipal.builder()
                .userId(UUID.fromString(claims.getSubject()))
                .accountId(accountId != null ? UUID.fromString(accountId) : null)
                .phone(claims.get("phone", String.class))
                .name(claims.get("name", String.class))
                .role(role != null ? UserRole.valueOf(role) : null)
                .scope(scope != null ? SecurityScope.valueOf(scope) : null)
                .accountStatus(accountStatus != null ? AccountStatus.valueOf(accountStatus) : null)
                .organizationId(organizationId != null ? UUID.fromString(organizationId) : null)
                .storeId(storeId != null ? UUID.fromString(storeId) : null)
                .build();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** @return "access" hoặc "refresh" (claim {@code type}); ném JwtException nếu token không hợp lệ. */
    public String getTokenType(String token) {
        return parseClaims(token).get("type", String.class);
    }

    public UUID getJti(String token) {
        String jti = parseClaims(token).getId();
        return jti != null ? UUID.fromString(jti) : null;
    }

    public Instant getExpiresAt(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String toClaim(UUID value) {
        return value != null ? value.toString() : null;
    }
}
