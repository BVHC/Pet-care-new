package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * Trạng thái online của nhân viên cho {@code StaffResponse} (BR-TN-06; docs/adr/0030, docs/adr/0091): có phiên chưa
 * hủy với access token còn hạn, tức {@code onlineUntil > now} ({@code onlineUntil} = hạn access token xa nhất của các
 * phiên chưa hủy, do {@code StaffProfileRepository} tính). Đúng mốc hạn là đã hết hạn (cùng quy ước với
 * {@code JwtTokenService.parse}). Cùng công thức với {@code StaffDirectoryService} (docs/adr/0024);
 * {@code StaffPresenceParityTest} giữ hai bên khớp nhau. Lớp đó không dùng helper này để không phải sửa constructor
 * của nó.
 */
@Component
class StaffPresence {

    private final Clock clock;

    StaffPresence(Clock clock) {
        this.clock = clock;
    }

    /** Mốc cho cả một lượt đọc: gọi một lần rồi dùng cho mọi dòng, để cùng trang có cùng "bây giờ". */
    Instant now() {
        return Instant.now(clock);
    }

    static boolean isOnline(Instant onlineUntil, Instant now) {
        return onlineUntil != null && onlineUntil.isAfter(now);
    }
}
