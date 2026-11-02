package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;

/**
 * Cài đặt {@link StaffDirectoryApi} (docs/adr/0024). Chỉ đọc, qua projection; tham gia transaction của bên gọi
 * ({@code REQUIRED}). Riêng {@link #findStaffForShare} khóa {@code accounts FOR SHARE} ({@code MANDATORY},
 * docs/adr/0061). "Đang hoạt động" = {@code status = ACTIVE} và không bị khóa; "online" = có phiên chưa hủy với access
 * token còn hạn ({@code onlineUntil > now}, BR-TN-06, docs/adr/0091).
 */
@Service
@Transactional(readOnly = true)
public class StaffDirectoryService implements StaffDirectoryApi {

    private final StaffProfileRepository staffProfiles;
    private final AccountRepository accounts;
    private final Clock clock;

    public StaffDirectoryService(StaffProfileRepository staffProfiles, AccountRepository accounts, Clock clock) {
        this.staffProfiles = staffProfiles;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public Optional<StaffSummary> findStaff(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Instant now = Instant.now(clock);
        return staffProfiles.findStaffRow(accountId).map(row -> toSummary(row, now));
    }

    /**
     * Ghi đè {@code readOnly} của class: khóa phải sống trong transaction ghi của bên gọi. Hai câu tách rời — khóa vô
     * hướng trước, đọc {@code staff_profiles} + {@code accounts} sau — để câu đọc lấy snapshot mới sau khi đã chờ khóa
     * (READ COMMITTED); gộp JOIN vào câu {@code FOR SHARE} thì Postgres chỉ đọc lại dòng bị khóa, có thể trả
     * {@code branch_id} cũ (docs/adr/0061).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StaffSummary> findStaffForShare(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("StaffDirectoryApi.findStaffForShare cần transaction ghi, không được readOnly");
        }
        if (accounts.lockForShare(accountId).isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        return staffProfiles.findStaffRow(accountId).map(row -> toSummary(row, now));
    }

    /** BR-TN-05, 06: người online trước, cùng nhóm thì theo họ tên rồi {@code accountId} (thứ tự của câu query). */
    @Override
    public List<StaffSummary> findAssignableStaff(Long branchId, Role role) {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(role, "role");
        Instant now = Instant.now(clock);
        return staffProfiles.findActiveStaffRows(branchId, role).stream()
                .map(row -> toSummary(row, now))
                .sorted(Comparator.comparing((StaffSummary staff) -> !staff.online()))
                .toList();
    }

    @Override
    public List<StaffSummary> findStaffByIds(Collection<Long> accountIds) {
        Objects.requireNonNull(accountIds, "accountIds");
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        return staffProfiles.findStaffRows(accountIds).stream().map(row -> toSummary(row, now)).toList();
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

    private static StaffSummary toSummary(StaffRow row, Instant now) {
        boolean active = row.status() == AccountStatus.ACTIVE && !row.locked();
        boolean online = row.onlineUntil() != null && row.onlineUntil().isAfter(now);
        return new StaffSummary(row.accountId(), row.fullName(), row.role(), row.branchId(), active, online);
    }
}
