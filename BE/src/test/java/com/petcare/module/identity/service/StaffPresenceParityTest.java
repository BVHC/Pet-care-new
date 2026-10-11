package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.platform.config.TimeConfig;

/**
 * Hai nơi tính "online" (BR-TN-06) phải cùng kết quả: {@link StaffPresence} (quản trị nhân viên, docs/adr/0030) và
 * {@link StaffDirectoryService} (docs/adr/0024, không sửa). Mốc biên: đúng mốc = online, trước 1 ns = offline,
 * {@code null} = offline.
 */
class StaffPresenceParityTest {

    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    private static final int WINDOW_MINUTES = 10;

    @Test
    void sameAnswerOnEveryBoundary() {
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.STAFF_ONLINE_WINDOW_MINUTES)).thenReturn(WINDOW_MINUTES);
        Clock clock = Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE);
        StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
        StaffDirectoryService directory = new StaffDirectoryService(staffProfiles, mock(AccountRepository.class),
                configs, clock);
        StaffPresence presence = new StaffPresence(configs, clock);
        Instant edge = NOW.minusSeconds(WINDOW_MINUTES * 60L);

        for (Instant lastSeen : Arrays.asList(edge, edge.minusNanos(1), edge.plusNanos(1), NOW, null)) {
            when(staffProfiles.findStaffRow(1L)).thenReturn(Optional.of(new StaffRow(1L, "A", Role.VET, 3L,
                    AccountStatus.ACTIVE, false, lastSeen)));

            boolean expected = directory.findStaff(1L).orElseThrow().online();

            assertThat(StaffPresence.isOnline(lastSeen, presence.onlineSince())).as(String.valueOf(lastSeen))
                    .isEqualTo(expected);
        }
        assertThat(StaffPresence.isOnline(edge, presence.onlineSince())).isTrue();
        assertThat(StaffPresence.isOnline(edge.minusNanos(1), presence.onlineSince())).isFalse();
    }
}
