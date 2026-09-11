package com.petcare.platform.security;

import com.petcare.platform.security.token.TokenBlacklistService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Xác thực request qua Bearer JWT: chỉ chấp nhận access-token (type=access,
 * xem JwtTokenProvider) chưa bị blacklist (TokenBlacklistService, fail-open khi
 * Redis sập — xem plan C:\Users\Admin\.claude\plans\c-tr-c-docs-silly-curry.md).
 * <p>
 * Filter KHÔNG tự trả response khi xác thực thất bại — chỉ set request attribute
 * {@code auth.error} với lý do cụ thể rồi để request đi tiếp unauthenticated;
 * {@link RestAuthenticationEntryPoint} là nơi duy nhất quyết định 401 cho endpoint
 * cần auth, đảm bảo endpoint public (permitAll) không bị chặn nhầm.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "auth.error";

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String jwt = getJwtFromRequest(request);

        if (StringUtils.hasText(jwt)) {
            authenticate(request, jwt);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String jwt) {
        try {
            String type = tokenProvider.getTokenType(jwt);
            if (!JwtTokenProvider.TOKEN_TYPE_ACCESS.equals(type)) {
                reject(request, "INVALID_TOKEN_TYPE");
                return;
            }

            UUID jti = tokenProvider.getJti(jwt);
            if (tokenBlacklistService.isBlacklisted(jti)) {
                reject(request, "TOKEN_REVOKED");
                return;
            }

            UserPrincipal userPrincipal = tokenProvider.parseToken(jwt);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userPrincipal,
                            null,
                            userPrincipal.getAuthorities()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException ex) {
            reject(request, "TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException ex) {
            reject(request, "INVALID_TOKEN");
        }
    }

    private void reject(HttpServletRequest request, String reason) {
        SecurityContextHolder.clearContext();
        request.setAttribute(AUTH_ERROR_ATTRIBUTE, reason);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
