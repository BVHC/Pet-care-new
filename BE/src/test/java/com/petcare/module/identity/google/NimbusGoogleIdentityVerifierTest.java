package com.petcare.module.identity.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.petcare.module.identity.exception.GoogleSignInUnavailableException;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.platform.exception.ErrorCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Bản thật của bộ kiểm ID token Google (docs/adr/0152, spec §6.4) với khóa RSA sinh trong test: không bao giờ gọi Google.
 * {@code NOW} là giây nguyên vì JWT cắt mốc thời gian về giây.
 */
class NimbusGoogleIdentityVerifierTest {

    private static final String CLIENT = "test-client.apps.googleusercontent.com";
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RSAKey KEY = newKey("google-key");
    private static final RSAKey OTHER_KEY = newKey("other-key");

    private final NimbusGoogleIdentityVerifier verifier = verifierTrusting(KEY);

    private static RSAKey newKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NimbusGoogleIdentityVerifier verifierTrusting(RSAKey key) {
        try {
            return new NimbusGoogleIdentityVerifier(CLIENT, CLOCK, NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("aud", CLIENT);
        c.put("sub", "1001");
        c.put("email", "lan@gmail.com");
        c.put("email_verified", true);
        c.put("name", "Nguyễn Lan");
        c.put("iat", Date.from(NOW));
        c.put("exp", Date.from(NOW.plusSeconds(3600)));
        return c;
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> c = claims();
        c.put(name, value);
        return c;
    }

    private static Map<String, Object> without(String... names) {
        Map<String, Object> c = claims();
        for (String name : names) {
            c.remove(name);
        }
        return c;
    }

    /** Token hết hạn phải có {@code iat} sớm hơn {@code exp}: Spring từ chối dựng Jwt khi exp không sau iat. */
    private static Map<String, Object> expiredAt(Instant exp) {
        Map<String, Object> c = with("exp", Date.from(exp));
        c.put("iat", Date.from(exp.minusSeconds(3600)));
        return c;
    }

    private static String sign(Map<String, Object> claims) {
        return sign(claims, KEY);
    }

    private static String sign(Map<String, Object> claims, RSAKey signer) {
        try {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder();
            claims.forEach(set::claim);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), set.build());
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token không chữ ký ({@code alg: none}): phải ra 401, không rơi vào nhánh 503. */
    private static String plain(Map<String, Object> claims) {
        JWTClaimsSet.Builder set = new JWTClaimsSet.Builder();
        claims.forEach(set::claim);
        return new PlainJWT(set.build()).serialize();
    }

    @Test
    void validTokenReturnsIdentity() {
        assertThat(verifier.verify(sign(claims())))
                .isEqualTo(new GoogleIdentity("1001", "lan@gmail.com", "Nguyễn Lan", true));
    }

    @Test
    void emailIsNormalized() {
        GoogleIdentity identity = verifier.verify(sign(with("email", " Lan@Gmail.com ")));

        assertThat(identity.email()).isEqualTo("lan@gmail.com");
        assertThat(identity.emailAuthoritative()).isTrue();
    }

    @Test
    void acceptsBareIssuer() {
        assertThat(verifier.verify(sign(with("iss", "accounts.google.com"))).sub()).isEqualTo("1001");
    }

    @Test
    void hdMakesOtherDomainAuthoritative() {
        Map<String, Object> corp = with("email", "a@corp.vn");

        assertThat(verifier.verify(sign(corp)).emailAuthoritative()).isFalse();

        corp.put("hd", "corp.vn");
        assertThat(verifier.verify(sign(corp)).emailAuthoritative()).isTrue();
    }

    @Test
    void blankHdDoesNotMakeOtherDomainAuthoritative() {
        assertThat(verifier.verify(sign(with("email", "a@corp.vn"))).emailAuthoritative()).isFalse();
        Map<String, Object> blankHd = with("email", "a@corp.vn");
        blankHd.put("hd", "");
        assertThat(verifier.verify(sign(blankHd)).emailAuthoritative()).isFalse();
        blankHd.put("hd", "   ");
        assertThat(verifier.verify(sign(blankHd)).emailAuthoritative()).isFalse();
    }

    @Test
    void audArrayContainingClientAccepted() {
        assertThat(verifier.verify(sign(with("aud", List.of("other.apps.googleusercontent.com", CLIENT)))).sub())
                .isEqualTo("1001");
    }

    @Test
    void emailVerifiedStringTrueAccepted() {
        assertThat(verifier.verify(sign(with("email_verified", "true"))).email()).isEqualTo("lan@gmail.com");
    }

    @Test
    void nameFallsBackToGivenAndFamily() {
        Map<String, Object> given = without("name");
        given.put("given_name", "Lan");
        given.put("family_name", "Nguyễn");

        assertThat(verifier.verify(sign(given)).name()).isEqualTo("Lan Nguyễn");
        assertThat(verifier.verify(sign(without("name"))).name()).isNull();
    }

    @Test
    void withinClockSkewAccepted() {
        assertThat(verifier.verify(sign(expiredAt(NOW.minusSeconds(30)))).sub()).isEqualTo("1001");
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("aud lạ", sign(with("aud", "someone-else.apps.googleusercontent.com"))),
                Arguments.of("iss lạ", sign(with("iss", "https://evil.example"))),
                Arguments.of("hết hạn quá 60 s", sign(expiredAt(NOW.minusSeconds(61)))),
                Arguments.of("email_verified false", sign(with("email_verified", false))),
                Arguments.of("thiếu email_verified", sign(without("email_verified"))),
                Arguments.of("thiếu email", sign(without("email"))),
                Arguments.of("thiếu sub", sign(without("sub"))),
                Arguments.of("thiếu exp", sign(without("exp"))),
                Arguments.of("aud mảng không chứa CLIENT", sign(with("aud", List.of("a.apps.googleusercontent.com", "b")))),
                Arguments.of("alg none (PlainJWT)", plain(claims())),
                Arguments.of("ký bằng khóa khác", sign(claims(), OTHER_KEY)),
                Arguments.of("chuỗi không phải JWT", "abc"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejectsAs401(String label, String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(InvalidCredentialsException.GOOGLE_MESSAGE);
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                });
    }

    /** {@code .env} trên Windows có thể để lại CR/khoảng trắng; lệch một ký tự là mọi token ra 401 khó chẩn đoán. */
    @Test
    void clientIdIsStripped() {
        assertThat(new GoogleProperties(" abc \r").clientId()).isEqualTo("abc");
    }

    @Test
    void blankClientIdIs503() {
        assertThat(new GoogleProperties(null).clientId()).isEmpty();
        NimbusGoogleIdentityVerifier unconfigured = new NimbusGoogleIdentityVerifier(new GoogleProperties(""), CLOCK);

        assertThatThrownBy(() -> unconfigured.verify(sign(claims())))
                .isInstanceOfSatisfying(GoogleSignInUnavailableException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo(
                            "Đăng nhập Google tạm thời không khả dụng. Vui lòng đăng nhập bằng email và mật khẩu");
                });
    }

    /** Decoder JWKS dựng lười (đã kiểm bytecode 6.5.11): khởi động BE không chạm mạng. */
    @Test
    void realConstructorWithClientIdBuilds() {
        assertThatCode(() -> new NimbusGoogleIdentityVerifier(new GoogleProperties(CLIENT), CLOCK)).doesNotThrowAnyException();
    }

    @Test
    void unreachableJwksIs503AndLogsNoToken() {
        NimbusGoogleIdentityVerifier offline = new NimbusGoogleIdentityVerifier(CLIENT, CLOCK,
                NimbusJwtDecoder.withJwkSetUri("http://127.0.0.1:1/certs").build());
        String token = sign(claims());
        Logger logger = (Logger) LoggerFactory.getLogger(NimbusGoogleIdentityVerifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> offline.verify(token))
                    .isInstanceOfSatisfying(GoogleSignInUnavailableException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("GOOGLE_JWKS_UNAVAILABLE").doesNotContain(token);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
