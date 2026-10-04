package com.petcare.platform.security;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Xác thực bằng JWT HS256 gắn với phiên lưu trong DB (docs/adr/0003). Stateless: không có HTTP session, mỗi request
 * mang bearer token và {@link JwtAuthenticationFilter} kiểm tra lại phiên, nên phiên bị hủy mất hiệu lực ngay ở request
 * kế tiếp. Phân quyền theo role bằng {@code @PreAuthorize} ở controller; phạm vi chi nhánh bằng {@link BranchScope}.
 * Controller nghiệp vụ tự ghi tiền tố {@code /api} trong {@code @RequestMapping}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Không cần đăng nhập: health, tài liệu API và các path public của contract (docs/api/00-method.md §3.1). */
    static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/api/auth/register", "/api/auth/register/**", "/api/auth/login", "/api/auth/password/**",
            "/api/public/**"
    };

    /**
     * Một matcher dùng chung cho {@code permitAll} và cho {@link JwtAuthenticationFilter} (bỏ qua token), để hai nơi
     * không thể lệch nhau: path public luôn xử lý như chưa đăng nhập (docs/adr/0005).
     */
    static final RequestMatcher PUBLIC_REQUESTS = new OrRequestMatcher(Arrays.stream(PUBLIC_PATHS)
            .map(path -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(path))
            .toList());

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtTokenService tokens,
            SessionAuthenticator sessionAuthenticator,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(tokens, sessionAuthenticator, resolver, PUBLIC_REQUESTS),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_REQUESTS).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
