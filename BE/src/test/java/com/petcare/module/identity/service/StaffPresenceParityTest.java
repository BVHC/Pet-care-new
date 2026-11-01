package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.platform.config.TimeConfig;

/**
 * Hai nơi tính "online" (BR-TN-06, docs/adr/0091) phải cùng kết quả: {@link StaffPresence} (quản trị nhân viên,
 * docs/adr/0030) và {@link StaffDirectoryService} (docs/adr/0024). Online ⇔ hạn access token xa nhất của phiên chưa hủy
 * còn sau bây giờ. Mốc biên: đúng mốc hạn = offline (như {@code JwtTokenService.parse}), sau 1 ns = online,
 * {@code null} (không có phiên) = offline.
 */
class StaffPresenceParityTest {

    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");

    @Test
    void sameAnswerOnEveryBoundary() {
        Clock clock = Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE);
        StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
        StaffDirectoryService directory = new StaffDirectoryService(staffProfiles, mock(AccountRepository.class),
                clock);
        StaffPresence presence = new StaffPresence(clock);

        for (Instant until : Arrays.asList(NOW.minusNanos(1), NOW, NOW.plusNanos(1), NOW.plusSeconds(600), null)) {
            when(staffProfiles.findStaffRow(1L)).thenReturn(Optional.of(new StaffRow(1L, "A", Role.VET, 3L,
                    AccountStatus.ACTIVE, false, until)));

            boolean expected = directory.findStaff(1L).orElseThrow().online();

            assertThat(StaffPresence.isOnline(until, presence.now())).as(String.valueOf(until)).isEqualTo(expected);
        }
        assertThat(StaffPresence.isOnline(NOW, presence.now())).isFalse();
        assertThat(StaffPresence.isOnline(NOW.plusNanos(1), presence.now())).isTrue();
        assertThat(StaffPresence.isOnline(null, presence.now())).isFalse();
    }
}
