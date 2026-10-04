package com.petcare.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * Phạm vi chi nhánh của người đang đăng nhập (04 nguyên tắc 8, docs/api/00-method.md §3.3, docs/adr/0003, 0006).
 * Service <b>tự gọi</b> với dữ liệu có {@code branch_id}; không có filter hay AOP tự áp, vì Customer, Pet, danh mục,
 * nhà cung cấp dùng chung toàn chuỗi. Không thay cho {@code @PreAuthorize} (role) hay kiểm tra chủ sở hữu của khách.
 * <ul>
 *   <li>{@link AccessScope#BRANCH} (A05–A08): luôn là chi nhánh của mình; chi nhánh khác → 403.</li>
 *   <li>{@link AccessScope#CHAIN} (ADMIN, SUPER_MANAGER): không giới hạn theo chi nhánh.</li>
 *   <li>{@link AccessScope#OWNER} (CUSTOMER): luôn 403. Dữ liệu của khách lọc theo chủ sở hữu ở service, nên gọi
 *       tới đây với khách là thiếu nhánh kiểm tra chủ sở hữu; từ chối để không trả dữ liệu toàn chuỗi.</li>
 * </ul>
 */
@Component
public class BranchScope {

    /** Người đang đăng nhập; gọi khi chưa xác thực là lỗi lập trình (endpoint đó phải yêu cầu đăng nhập). */
    public SecurityPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityPrincipal principal) {
            return principal;
        }
        throw new IllegalStateException("No authenticated SecurityPrincipal in SecurityContext");
    }

    /**
     * Chi nhánh hiệu lực cho truy vấn danh sách / thống kê. A05–A08: chi nhánh của mình ({@code requested} null hoặc
     * trùng); khác → {@link AccessDeniedScopeException}. ADMIN, SUPER_MANAGER: trả nguyên {@code requested}
     * ({@code null} = toàn chuỗi), ví dụ báo cáo BR-BC-01. Khách → {@link AccessDeniedScopeException}.
     */
    public Long resolve(Long requestedBranchId) {
        SecurityPrincipal principal = current();
        return switch (principal.accessScope()) {
            case CHAIN -> requestedBranchId;
            case BRANCH -> {
                Long own = ownBranch(principal);
                if (requestedBranchId != null && !requestedBranchId.equals(own)) {
                    throw new AccessDeniedScopeException("branch:" + requestedBranchId, "branch:" + own);
                }
                yield own;
            }
            case OWNER -> throw ownerDenied(requestedBranchId, principal);
        };
    }

    /**
     * Kiểm tra một bản ghi cụ thể đã tải. A05–A08 chỉ được bản ghi của chi nhánh mình; bản ghi không gắn chi nhánh
     * ({@code null}) cũng bị từ chối, ví dụ feedback không gắn chi nhánh chỉ SUPER_MANAGER xem (BR-DG-03).
     * Khách luôn bị từ chối.
     */
    public void check(Long resourceBranchId) {
        SecurityPrincipal principal = current();
        switch (principal.accessScope()) {
            case CHAIN -> {
            }
            case BRANCH -> {
                Long own = ownBranch(principal);
                if (!own.equals(resourceBranchId)) {
                    throw new AccessDeniedScopeException("branch:" + resourceBranchId, "branch:" + own);
                }
            }
            case OWNER -> throw ownerDenied(resourceBranchId, principal);
        }
    }

    /** A05–A08 luôn thuộc đúng 1 chi nhánh (BR-QT-03); thiếu là dữ liệu sai, không được coi là toàn chuỗi. */
    private static Long ownBranch(SecurityPrincipal principal) {
        Long own = principal.branchId();
        if (own == null) {
            throw new IllegalStateException("Branch-scoped account " + principal.accountId() + " has no branch");
        }
        return own;
    }

    /** docs/adr/0006: khách không có phạm vi chi nhánh. */
    private static AccessDeniedScopeException ownerDenied(Long branchId, SecurityPrincipal principal) {
        return new AccessDeniedScopeException("branch:" + branchId, "owner:" + principal.accountId());
    }
}
