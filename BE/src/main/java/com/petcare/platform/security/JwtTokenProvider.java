package com.petcare.platform.security;

import com.petcare.platform.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

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

    public String generateAccessToken(UserPrincipal user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlMs);

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .claim("accountId", toClaim(user.getAccountId()))
                .claim("phone", user.getPhone())
                .claim("name", user.getName())
                .claim("role", user.getRole() != null ? user.getRole().name() : null)
                .claim("organizationId", toClaim(user.getOrganizationId()))
                .claim("storeId", toClaim(user.getStoreId()))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(UserPrincipal user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenTtlMs);

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public UserPrincipal parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String accountId = claims.get("accountId", String.class);
        String role = claims.get("role", String.class);
        String organizationId = claims.get("organizationId", String.class);
        String storeId = claims.get("storeId", String.class);

        return UserPrincipal.builder()
                .userId(UUID.fromString(claims.getSubject()))
                .accountId(accountId != null ? UUID.fromString(accountId) : null)
                .phone(claims.get("phone", String.class))
                .name(claims.get("name", String.class))
                .role(role != null ? UserRole.valueOf(role) : null)
                .organizationId(organizationId != null ? UUID.fromString(organizationId) : null)
                .storeId(storeId != null ? UUID.fromString(storeId) : null)
                .build();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private String toClaim(UUID value) {
        return value != null ? value.toString() : null;
    }
}
