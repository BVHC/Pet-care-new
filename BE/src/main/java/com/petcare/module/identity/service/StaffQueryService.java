package com.petcare.module.identity.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC08 — xem nhân viên (identity-v1 #15, #16 phần đọc lại, #17; docs/adr/0030). Phạm vi xem không qua
 * {@code StaffHierarchyPolicy} và 403 ở đây không audit (docs/adr/0029 mục 1):
 * <ul>
 *   <li>ADMIN thấy SUPER_MANAGER; SUPER_MANAGER thấy A05–A08 mọi chi nhánh; BRANCH_MANAGER thấy A06–A08 chi nhánh
 *       mình (contract #15).</li>
 *   <li>Lọc {@code role} ngoài tập được thấy → trang rỗng; {@code branchId} khác chi nhánh của BRANCH_MANAGER → 403
 *       ({@link BranchScope#resolve}); ADMIN lọc {@code branchId} → rỗng vì SUPER_MANAGER không có chi nhánh.</li>
 * </ul>
 * Chỉ đọc, không khóa; quyền kiểm trên chính dòng vừa đọc.
 */
@Service
@Transactional(readOnly = true)
public class StaffQueryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<AccountStatus> STAFF_STATUSES = EnumSet.of(AccountStatus.ACTIVE, AccountStatus.DISABLED);

    private final StaffProfileRepository staffProfiles;
    private final BranchScope branchScope;
    private final StaffAdminMapper mapper;
    private final StaffPresence presence;

    public StaffQueryService(StaffProfileRepository staffProfiles, BranchScope branchScope, StaffAdminMapper mapper,
            StaffPresence presence) {
        this.staffProfiles = staffProfiles;
        this.branchScope = branchScope;
        this.mapper = mapper;
        this.presence = presence;
    }

    /** identity-v1 #15. {@code page} ≥ 0, {@code size} 1–100 (kẹp như catalog, docs/api/00-method.md §3.4). */
    public PageResponse<StaffResponse> listStaff(Long branchId, Role role, AccountStatus status, String q, int page,
            int size) {
        Set<Role> visible = visibleRoles(branchScope.current());
        Long effectiveBranchId = branchScope.resolve(branchId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        Set<Role> roles = role == null ? visible : visible.contains(role) ? EnumSet.of(role) : EnumSet.noneOf(Role.class);
        Set<AccountStatus> statuses = status == null ? STAFF_STATUSES
                : STAFF_STATUSES.contains(status) ? EnumSet.of(status) : EnumSet.noneOf(AccountStatus.class);
        if (roles.isEmpty() || statuses.isEmpty()) {
            return PageResponse.of(Page.<StaffResponse>empty(pageable));
        }

        Instant onlineSince = presence.onlineSince();
        return PageResponse.of(staffProfiles.findStaffPage(roles, statuses, effectiveBranchId, likePattern(q), pageable)
                .map(row -> toResponse(row, onlineSince)));
    }

    /**
     * identity-v1 #17. Không có hoặc là tài khoản khách → 404 (cùng message); ngoài phạm vi xem → 403 không audit.
     */
    public StaffResponse getStaff(Long accountId) {
        StaffDetailRow row = staffProfiles.findStaffDetail(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", accountId));
        SecurityPrincipal principal = branchScope.current();
        if (!visibleRoles(principal).contains(row.role())) {
            throw new AccessDeniedScopeException("staff:" + row.role(), "role:" + principal.role());
        }
        if (principal.branchScoped()) {
            branchScope.check(row.branchId());
        }
        return toResponse(row, presence.onlineSince());
    }

    /** Role ngoài A03–A05 đã bị {@code @PreAuthorize} chặn; nếu lọt tới đây thì không thấy ai (từ chối mặc định). */
    static Set<Role> visibleRoles(SecurityPrincipal principal) {
        return switch (Role.valueOf(principal.role())) {
            case ADMIN -> EnumSet.of(Role.SUPER_MANAGER);
            case SUPER_MANAGER -> EnumSet.copyOf(StaffHierarchyPolicy.BRANCH_STAFF);
            case BRANCH_MANAGER -> EnumSet.copyOf(StaffHierarchyPolicy.BRANCH_SUBORDINATES);
            default -> EnumSet.noneOf(Role.class);
        };
    }

    /**
     * Tìm theo họ tên hoặc email, không phân biệt hoa thường. Ký tự escape là {@code !} (khớp {@code ESCAPE '!'} của
     * {@code StaffProfileRepository.findStaffPage}); {@code !}, {@code %}, {@code _} trong chuỗi tìm được escape.
     */
    static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private StaffResponse toResponse(StaffDetailRow row, Instant onlineSince) {
        return mapper.toResponse(row, StaffPresence.isOnline(row.lastSeenAt(), onlineSince));
    }
}
