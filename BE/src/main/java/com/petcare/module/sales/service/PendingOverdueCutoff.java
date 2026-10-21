package com.petcare.module.sales.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;

/**
 * Mốc "Order {@code PENDING} quá hạn" (BR-BH-05; docs/adr/0051, 0064): Order chốt trước 00:00 giờ Việt Nam của ngày
 * (hôm nay − N + 1), N = [CFG] {@code order.pending_alert_days} (mặc định 1 = chốt từ hôm qua trở về trước). Một nơi
 * duy nhất cho danh sách {@code pendingOverdue} (#2) và thông báo cuối ngày của ST13, để hai nơi không lệch định nghĩa.
 */
@Component
@RequiredArgsConstructor
public class PendingOverdueCutoff {

    private final SystemConfigApi configs;
    private final Clock clock;

    public Instant cutoff() {
        return LocalDate.now(clock).minusDays(days() - 1L).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** N hiện hành của [CFG]; đọc lúc gọi (áp dụng từ lượt kiểm kế tiếp, ADR-0057). */
    public int days() {
        return configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS);
    }
}
