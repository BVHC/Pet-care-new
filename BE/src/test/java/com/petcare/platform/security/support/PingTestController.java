package com.petcare.platform.security.support;

import com.petcare.platform.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint test-only để xác minh JwtAuthenticationFilter/SecurityConfig mà không
 * cần Login thật (Account/User entity chưa tồn tại). Không nằm dưới /api/auth/**
 * nên yêu cầu authentication theo SecurityConfig mặc định.
 */
@RestController
public class PingTestController {

    @GetMapping("/api/test/ping")
    public String ping(@AuthenticationPrincipal UserPrincipal principal) {
        return "pong:" + principal.getPhone();
    }
}
