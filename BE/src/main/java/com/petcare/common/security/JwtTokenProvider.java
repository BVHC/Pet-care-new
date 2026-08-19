package com.petcare.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

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
                .subject(String.valueOf(user.getUserId()))
                .claim("accountId", user.getAccountId())
                .claim("phone", user.getPhone())
                .claim("name", user.getName())
                .claim("role", user.getRole())
                .claim("organizationId", user.getOrganizationId())
                .claim("storeId", user.getStoreId())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(UserPrincipal user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenTtlMs);

        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
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

        return UserPrincipal.builder()
                .userId(Long.parseLong(claims.getSubject()))
                .accountId(claims.get("accountId", Long.class))
                .phone(claims.get("phone", String.class))
                .name(claims.get("name", String.class))
                .role(claims.get("role", String.class))
                .organizationId(claims.get("organizationId", Long.class))
                .storeId(claims.get("storeId", Long.class))
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
}
