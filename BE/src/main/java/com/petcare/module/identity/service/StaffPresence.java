package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Trạng thái online của nhân viên cho {@code StaffResponse} (BR-TN-06; docs/adr/0030): có thao tác trong
 * {@code staff.online_window_minutes} [CFG] tính tới bây giờ, đúng mốc vẫn là online. Cùng công thức với
 * {@code StaffDirectoryService} (docs/adr/0024); {@code StaffPresenceParityTest} giữ hai bên khớp nhau. Lớp đó không
 * dùng helper này để không phải sửa constructor của nó.
 */
@Component
class StaffPresence {

    private final SystemConfigApi configs;
    private final Clock clock;

    StaffPresence(SystemConfigApi configs, Clock clock) {
        this.configs = configs;
        this.clock = clock;
    }

    /** Mốc cho cả một lượt đọc: gọi một lần rồi dùng cho mọi dòng, để cùng trang có cùng "bây giờ". */
    Instant onlineSince() {
        return Instant.now(clock).minus(Duration.ofMinutes(configs.getInt(ConfigKey.STAFF_ONLINE_WINDOW_MINUTES)));
    }

    static boolean isOnline(Instant lastSeenAt, Instant onlineSince) {
        return lastSeenAt != null && !lastSeenAt.isBefore(onlineSince);
    }
}
