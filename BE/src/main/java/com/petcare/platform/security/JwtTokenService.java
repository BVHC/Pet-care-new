package com.petcare.platform.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Ký và kiểm tra access token JWT HS256 (docs/adr/0003). Token chỉ mang định danh ({@code sub}, {@code sid},
 * {@code jti}); việc phiên còn hiệu lực hay không do {@link SessionAuthenticator} quyết định ở mỗi request.
 * Thời gian lấy từ bean {@link Clock} để test được với {@code Clock.fixed}.
 */
@Component
public class JwtTokenService {

    public static final String ISSUER = "petcare-api";
    static final String SESSION_ID_CLAIM = "sid";
    private static final String ALGORITHM = "HS256";

    private final SecretKey key;
    private final Clock clock;
    private final JwtParser parser;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /** {@code exp} = {@code sessions.expires_at}; hai mốc thời gian được làm tròn xuống giây theo chuẩn JWT. */
    public String issue(long accountId, long sessionId, String jti, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(Long.toString(accountId))
                .claim(SESSION_ID_CLAIM, sessionId)
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Token sai chữ ký, sai issuer, khác HS256, hết hạn (kể cả đúng bằng {@code exp}) hoặc thiếu {@code sub},
     * {@code sid}, {@code jti} đều trả {@code empty}; không ném exception ra ngoài.
     */
    public Optional<TokenClaims> parse(String token) {
        try {
            Jws<Claims> jws = parser.parseSignedClaims(token);
            if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            Claims claims = jws.getPayload();
            Date expiration = claims.getExpiration();
            if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
                return Optional.empty();
            }
            Long accountId = parseLong(claims.getSubject());
            Long sessionId = claims.get(SESSION_ID_CLAIM) instanceof Number number ? number.longValue() : null;
            String jti = claims.getId();
            if (accountId == null || sessionId == null || jti == null || jti.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(accountId, sessionId, jti));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
