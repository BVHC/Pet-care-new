package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;

/**
 * Cài đặt {@link StaffDirectoryApi} (docs/adr/0024). Chỉ đọc, qua projection; tham gia transaction của bên gọi
 * ({@code REQUIRED}). "Đang hoạt động" = {@code status = ACTIVE} và không bị khóa; "online" = {@code last_seen_at} trong
 * {@code staff.online_window_minutes} [CFG] tính tới bây giờ, đúng bằng mốc vẫn là online (BR-TN-06).
 */
@Service
@Transactional(readOnly = true)
public class StaffDirectoryService implements StaffDirectoryApi {

    private final StaffProfileRepository staffProfiles;
    private final AccountRepository accounts;
    private final SystemConfigApi configs;
    private final Clock clock;

    public StaffDirectoryService(StaffProfileRepository staffProfiles, AccountRepository accounts,
            SystemConfigApi configs, Clock clock) {
        this.staffProfiles = staffProfiles;
        this.accounts = accounts;
        this.configs = configs;
        this.clock = clock;
    }

    @Override
    public Optional<StaffSummary> findStaff(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Instant onlineSince = onlineSince();
        return staffProfiles.findStaffRow(accountId).map(row -> toSummary(row, onlineSince));
    }

    /** BR-TN-05, 06: người online trước, cùng nhóm thì theo họ tên rồi {@code accountId} (thứ tự của câu query). */
    @Override
    public List<StaffSummary> findAssignableStaff(Long branchId, Role role) {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(role, "role");
        Instant onlineSince = onlineSince();
        return staffProfiles.findActiveStaffRows(branchId, role).stream()
                .map(row -> toSummary(row, onlineSince))
                .sorted(Comparator.comparing((StaffSummary staff) -> !staff.online()))
                .toList();
    }

    @Override
    public int countBranchManagers(Long branchId) {
        Objects.requireNonNull(branchId, "branchId");
        return Math.toIntExact(staffProfiles.countActiveBranchManagers(branchId));
    }

    @Override
    public List<Long> findActiveStaffIds(Long branchId, Role role) {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(role, "role");
        return staffProfiles.findActiveStaffRows(branchId, role).stream().map(StaffRow::accountId).toList();
    }

    @Override
    public List<Long> findActiveSuperManagerIds() {
        return accounts.findActiveIdsByRole(Role.SUPER_MANAGER);
    }

    @Override
    public List<PublicVetProfile> listPublicVets() {
        return staffProfiles.findPublicVets().stream()
                .map(row -> new PublicVetProfile(row.accountId(), row.fullName(), row.avatarUrl(), row.specialty(),
                        row.bio(), row.branchId()))
                .toList();
    }

    @Override
    public Optional<String> findEmail(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.findEmailById(accountId);
    }

    private Instant onlineSince() {
        return Instant.now(clock).minus(Duration.ofMinutes(configs.getInt(ConfigKey.STAFF_ONLINE_WINDOW_MINUTES)));
    }

    private static StaffSummary toSummary(StaffRow row, Instant onlineSince) {
        boolean active = row.status() == AccountStatus.ACTIVE && !row.locked();
        boolean online = row.lastSeenAt() != null && !row.lastSeenAt().isBefore(onlineSince);
        return new StaffSummary(row.accountId(), row.fullName(), row.role(), row.branchId(), active, online);
    }
}
