package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.petcare.platform.config.TimeConfig;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** docs/adr/0003 D1: HS256, claims định danh, hết hạn theo bean Clock, mọi token sai → empty. */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-key-for-hs256-signing-0123456789";
    private static final Instant ISSUED = Instant.parse("2026-10-06T01:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-06T13:00:00Z");

    private static JwtTokenService serviceAt(Instant now) {
        return serviceAt(SECRET, now);
    }

    private static JwtTokenService serviceAt(String secret, Instant now) {
        return new JwtTokenService(new JwtProperties(secret), Clock.fixed(now, TimeConfig.BUSINESS_ZONE));
    }

    @Test
    void roundTripCarriesSubjectSessionAndJti() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);

        assertThat(serviceAt(ISSUED).parse(token)).contains(new TokenClaims(42L, 7L, "jti-abc"));
    }

    @Test
    void tokenHasIssuerAndHs256Header() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(payload).contains("\"iss\":\"petcare-api\"", "\"sub\":\"42\"", "\"sid\":7", "\"jti\":\"jti-abc\"")
                .doesNotContain("role", "branch");
    }

    @Test
    void validOneSecondBeforeExpiry() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);

        assertThat(serviceAt(EXPIRES.minusSeconds(1)).parse(token)).isPresent();
    }

    @Test
    void rejectedExactlyAtExpiry() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);

        assertThat(serviceAt(EXPIRES).parse(token)).isEmpty();
    }

    @Test
    void rejectedAfterExpiry() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);

        assertThat(serviceAt(EXPIRES.plusSeconds(1)).parse(token)).isEmpty();
    }

    @Test
    void rejectedWhenSignedWithAnotherKey() {
        String token = serviceAt("another-secret-key-for-hs256-signing-abcdefghij", ISSUED)
                .issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);

        assertThat(serviceAt(ISSUED).parse(token)).isEmpty();
    }

    @Test
    void rejectedWhenTampered() {
        String token = serviceAt(ISSUED).issue(42L, 7L, "jti-abc", ISSUED, EXPIRES);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"sub\":\"42\"", "\"sub\":\"1\"").getBytes(StandardCharsets.UTF_8));

        assertThat(serviceAt(ISSUED).parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void rejectedWhenUnsigned() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"iss\":\"petcare-api\",\"sub\":\"42\",\"sid\":7,\"jti\":\"x\",\"exp\":"
                + EXPIRES.getEpochSecond() + "}");

        assertThat(serviceAt(ISSUED).parse(header + "." + payload + ".")).isEmpty();
    }

    @Test
    void rejectedWhenAlgorithmIsNotHs256EvenWithSameSecret() {
        String longSecret = SECRET + SECRET;
        String hs512 = Jwts.builder().issuer(JwtTokenService.ISSUER).subject("42")
                .claim(JwtTokenService.SESSION_ID_CLAIM, 7L).id("jti-abc").expiration(Date.from(EXPIRES))
                .signWith(Keys.hmacShaKeyFor(longSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512)
                .compact();

        assertThat(serviceAt(longSecret, ISSUED).parse(hs512)).isEmpty();
    }

    @Test
    void rejectedWithWrongIssuer() {
        String token = builder().issuer("someone-else").subject("42")
                .claim(JwtTokenService.SESSION_ID_CLAIM, 7L).id("jti").compact();

        assertThat(serviceAt(ISSUED).parse(token)).isEmpty();
    }

    @Test
    void rejectedWhenSessionIdMissing() {
        assertThat(serviceAt(ISSUED).parse(builder().subject("42").id("jti").compact())).isEmpty();
    }

    @Test
    void rejectedWhenJtiMissing() {
        String token = builder().subject("42").claim(JwtTokenService.SESSION_ID_CLAIM, 7L).compact();

        assertThat(serviceAt(ISSUED).parse(token)).isEmpty();
    }

    @Test
    void rejectedWhenSubjectMissingOrNotNumeric() {
        String noSubject = builder().claim(JwtTokenService.SESSION_ID_CLAIM, 7L).id("jti").compact();
        String textSubject = builder().subject("admin").claim(JwtTokenService.SESSION_ID_CLAIM, 7L).id("jti")
                .compact();

        assertThat(serviceAt(ISSUED).parse(noSubject)).isEmpty();
        assertThat(serviceAt(ISSUED).parse(textSubject)).isEmpty();
    }

    @Test
    void rejectedWhenExpirationMissing() {
        String token = Jwts.builder().issuer(JwtTokenService.ISSUER).subject("42")
                .claim(JwtTokenService.SESSION_ID_CLAIM, 7L).id("jti")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertThat(serviceAt(ISSUED).parse(token)).isEmpty();
    }

    @Test
    void garbageIsRejectedWithoutException() {
        assertThat(serviceAt(ISSUED).parse("not-a-jwt")).isEmpty();
        assertThat(serviceAt(ISSUED).parse("a.b.c")).isEmpty();
    }

    @Test
    void secretShorterThan32BytesFailsAtStartup() {
        assertThatThrownBy(() -> new JwtProperties("x".repeat(31)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
        assertThatThrownBy(() -> new JwtProperties(null)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new JwtProperties("x".repeat(32))).doesNotThrowAnyException();
    }

    /** Token HS256 đúng khóa, đúng issuer, còn hạn; test tự bỏ/đổi từng claim. */
    private static io.jsonwebtoken.JwtBuilder builder() {
        return Jwts.builder().issuer(JwtTokenService.ISSUER).expiration(Date.from(EXPIRES))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
