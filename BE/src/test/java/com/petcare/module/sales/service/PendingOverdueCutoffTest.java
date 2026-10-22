package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/** Mốc "Order PENDING quá hạn" dùng chung cho danh sách {@code pendingOverdue} và báo cuối ngày ST13 (BR-BH-05). */
class PendingOverdueCutoffTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SystemConfigApi configs = mock(SystemConfigApi.class);

    private PendingOverdueCutoff at(String utc) {
        return new PendingOverdueCutoff(configs, Clock.fixed(Instant.parse(utc), ZONE));
    }

    @Test
    void oneDayMeansBeforeMidnightOfTodayVietnamTime() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(1);

        // 2026-10-21 08:30 giờ Việt Nam → mốc 00:00 ngày 21 (= 17:00Z ngày 20)
        assertThat(at("2026-10-21T01:30:00Z").cutoff()).isEqualTo(Instant.parse("2026-10-20T17:00:00Z"));
    }

    @Test
    void threeDaysMeansBeforeMidnightOfTheDayBeforeYesterday() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(3);

        assertThat(at("2026-10-21T01:30:00Z").cutoff()).isEqualTo(Instant.parse("2026-10-18T17:00:00Z"));
    }

    @Test
    void justBeforeAndJustAfterVietnamMidnightFallOnDifferentDays() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(1);

        // 23:59 ngày 21 giờ VN = 16:59Z ngày 21 → vẫn là ngày 21; 00:00 ngày 22 = 17:00Z ngày 21 → sang ngày 22
        assertThat(at("2026-10-21T16:59:00Z").cutoff()).isEqualTo(Instant.parse("2026-10-20T17:00:00Z"));
        assertThat(at("2026-10-21T17:00:00Z").cutoff()).isEqualTo(Instant.parse("2026-10-21T17:00:00Z"));
    }

    @Test
    void daysComesFromTheConfigurationAtCallTime() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(7);

        assertThat(at("2026-10-21T01:30:00Z").days()).isEqualTo(7);
    }
}
