package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi.PublicVetProfile;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.PublicVetRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.platform.config.TimeConfig;

/** docs/adr/0024: định nghĩa {@code active}, {@code online} (BR-TN-06), thứ tự gán (BR-TN-05), đếm quản lý (BR-QT-04). */
class StaffDirectoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final StaffDirectoryService service = new StaffDirectoryService(staffProfiles, accounts, configs,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.STAFF_ONLINE_WINDOW_MINUTES)).thenReturn(10);
    }

    @Test
    void activeMeansStatusActiveAndNotLocked() {
        assertThat(summaryOf(row(1L, AccountStatus.ACTIVE, false, null)).active()).isTrue();
        assertThat(summaryOf(row(2L, AccountStatus.ACTIVE, true, null)).active()).isFalse();
        assertThat(summaryOf(row(3L, AccountStatus.DISABLED, false, null)).active()).isFalse();
    }

    @Test
    void onlineWindowIncludesItsBoundaryAndComesFromConfig() {
        assertThat(summaryOf(row(1L, AccountStatus.ACTIVE, false, null)).online()).as("chưa từng thao tác").isFalse();
        assertThat(summaryOf(row(2L, AccountStatus.ACTIVE, false, NOW.minusSeconds(600))).online())
                .as("đúng mốc 10 phút").isTrue();
        assertThat(summaryOf(row(3L, AccountStatus.ACTIVE, false, NOW.minusSeconds(601))).online())
                .as("quá mốc 1 giây").isFalse();

        when(configs.getInt(ConfigKey.STAFF_ONLINE_WINDOW_MINUTES)).thenReturn(15);
        assertThat(summaryOf(row(4L, AccountStatus.ACTIVE, false, NOW.minusSeconds(601))).online())
                .as("[CFG] đổi thì cửa sổ đổi").isTrue();
    }

    @Test
    void findStaffIsEmptyWhenAccountHasNoStaffProfile() {
        when(staffProfiles.findStaffRow(9L)).thenReturn(Optional.empty());

        assertThat(service.findStaff(9L)).isEmpty();
    }

    @Test
    void assignableStaffPutsOnlineFirstAndKeepsQueryOrderWithinGroup() {
        when(staffProfiles.findActiveStaffRows(5L, Role.VET)).thenReturn(List.of(
                vet(1L, "An", null),
                vet(2L, "Bình", NOW.minusSeconds(60)),
                vet(3L, "Chi", NOW.minusSeconds(3600)),
                vet(4L, "Dũng", NOW)));

        assertThat(service.findAssignableStaff(5L, Role.VET))
                .extracting(StaffSummary::accountId).containsExactly(2L, 4L, 1L, 3L);
    }

    @Test
    void activeStaffIdsKeepQueryOrder() {
        when(staffProfiles.findActiveStaffRows(5L, Role.RECEPTIONIST)).thenReturn(List.of(
                new StaffRow(7L, "An", Role.RECEPTIONIST, 5L, AccountStatus.ACTIVE, false, NOW),
                new StaffRow(3L, "Bình", Role.RECEPTIONIST, 5L, AccountStatus.ACTIVE, false, null)));

        assertThat(service.findActiveStaffIds(5L, Role.RECEPTIONIST)).containsExactly(7L, 3L);
    }

    @Test
    void countBranchManagersPassesRepositoryCount() {
        when(staffProfiles.countActiveBranchManagers(5L)).thenReturn(2L);

        assertThat(service.countBranchManagers(5L)).isEqualTo(2);
    }

    @Test
    void superManagersAndEmailComeFromAccounts() {
        when(accounts.findActiveIdsByRole(Role.SUPER_MANAGER)).thenReturn(List.of(1L, 2L));
        when(accounts.findEmailById(9L)).thenReturn(Optional.of("khach@petcare.test"));

        assertThat(service.findActiveSuperManagerIds()).containsExactly(1L, 2L);
        assertThat(service.findEmail(9L)).contains("khach@petcare.test");
    }

    @Test
    void publicVetsMapEveryField() {
        when(staffProfiles.findPublicVets()).thenReturn(List.of(
                new PublicVetRow(4L, "Bác sĩ An", "https://img/a.png", "Nội khoa", "10 năm kinh nghiệm", 5L)));

        assertThat(service.listPublicVets()).containsExactly(
                new PublicVetProfile(4L, "Bác sĩ An", "https://img/a.png", "Nội khoa", "10 năm kinh nghiệm", 5L));
    }

    private StaffSummary summaryOf(StaffRow row) {
        when(staffProfiles.findStaffRow(row.accountId())).thenReturn(Optional.of(row));
        return service.findStaff(row.accountId()).orElseThrow();
    }

    private static StaffRow row(Long id, AccountStatus status, boolean locked, Instant lastSeenAt) {
        return new StaffRow(id, "Nhân viên " + id, Role.VET, 5L, status, locked, lastSeenAt);
    }

    private static StaffRow vet(Long id, String name, Instant lastSeenAt) {
        return new StaffRow(id, name, Role.VET, 5L, AccountStatus.ACTIVE, false, lastSeenAt);
    }
}
