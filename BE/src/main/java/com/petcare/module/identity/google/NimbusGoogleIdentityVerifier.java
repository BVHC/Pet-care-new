package com.petcare.module.identity.google;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.petcare.module.identity.exception.GoogleSignInUnavailableException;
import com.petcare.module.identity.exception.InvalidCredentialsException;

import lombok.extern.slf4j.Slf4j;

/**
 * Kiểm ID token Google bằng JWKS của Google (docs/adr/0152, spec §6.4). Tự dựng decoder, không khai báo
 * {@code @Bean JwtDecoder} để không bị nơi khác tiêm nhầm. Phân loại lỗi: {@link BadJwtException} (chữ ký, thời hạn, claim
 * sai) thành 401; {@link JwtException} khác (không lấy được JWKS) thành 503. Không dùng {@code nonce}: đánh đổi đã chấp
 * nhận ở ADR-0152.
 */
@Slf4j
@Component
@EnableConfigurationProperties(GoogleProperties.class)
public class NimbusGoogleIdentityVerifier implements GoogleIdentityVerifier {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String clientId;
    private final NimbusJwtDecoder decoder;

    @Autowired
    public NimbusGoogleIdentityVerifier(GoogleProperties properties, Clock clock) {
        this(properties.clientId(), clock, jwksDecoder());
    }

    NimbusGoogleIdentityVerifier(String clientId, Clock clock, NimbusJwtDecoder decoder) {
        this.clientId = clientId;
        this.decoder = decoder;
        decoder.setJwtValidator(validator(clientId, clock));
    }

    /** Mặc định của Spring là {@code new RestTemplate()} không timeout; không {@code .cache(...)} vì Nimbus tự cache JWKS. */
    private static NimbusJwtDecoder jwksDecoder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HTTP_TIMEOUT);
        factory.setReadTimeout(HTTP_TIMEOUT);
        return NimbusJwtDecoder.withJwkSetUri(JWKS_URI).restOperations(new RestTemplate(factory)).build();
    }

    /** Predicate nhận {@code null} khi thiếu claim, nên mọi predicate đều chịu được {@code null}. */
    static OAuth2TokenValidator<Jwt> validator(String clientId, Clock clock) {
        JwtTimestampValidator timestamp = new JwtTimestampValidator(CLOCK_SKEW);
        timestamp.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                timestamp,
                // JwtTimestampValidator (6.5.11) bỏ qua kiểm khi thiếu exp: token Google luôn có exp nên bắt buộc có mặt.
                new JwtClaimValidator<Object>("exp", Objects::nonNull),
                new JwtClaimValidator<Object>("iss", v -> ISSUERS.contains(String.valueOf(v))),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(clientId)),
                new JwtClaimValidator<Object>("email_verified", v -> Boolean.TRUE.equals(v) || "true".equals(v)),
                new JwtClaimValidator<Object>("sub", NimbusGoogleIdentityVerifier::present),
                new JwtClaimValidator<Object>("email", NimbusGoogleIdentityVerifier::present));
    }

    private static boolean present(Object v) {
        return v != null && !String.valueOf(v).isBlank();
    }

    @Override
    public GoogleIdentity verify(String credential) {
        if (clientId.isBlank()) {
            throw new GoogleSignInUnavailableException();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(credential);
        } catch (BadJwtException e) {
            throw new InvalidCredentialsException(InvalidCredentialsException.GOOGLE_MESSAGE);
        } catch (JwtException e) {
            // Chỉ ghi loại lỗi và message của Spring (không chứa token), không ghi stack trace.
            log.warn("GOOGLE_JWKS_UNAVAILABLE {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw new GoogleSignInUnavailableException();
        }
        String email = jwt.getClaimAsString("email").strip().toLowerCase(Locale.ROOT);
        String hd = jwt.getClaimAsString("hd");
        return new GoogleIdentity(jwt.getSubject(), email, displayName(jwt),
                email.endsWith("@gmail.com") || hd != null && !hd.isBlank());
    }

    /** {@code name}; trống thì {@code given_name + " " + family_name}; trống nữa thì {@code null}. */
    private static String displayName(Jwt jwt) {
        String name = strip(jwt.getClaimAsString("name"));
        if (!name.isEmpty()) {
            return name;
        }
        String joined = Stream.of(jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"))
                .map(NimbusGoogleIdentityVerifier::strip)
                .filter(s -> !s.isEmpty())
                .reduce((given, family) -> given + " " + family)
                .orElse("");
        return joined.isEmpty() ? null : joined;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
