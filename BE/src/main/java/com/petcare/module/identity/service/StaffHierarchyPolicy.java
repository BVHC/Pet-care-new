package com.petcare.module.identity.service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Ma trận phân cấp quản trị nhân viên (UC08; docs/adr/0029 mục 1). Thuần logic: không đọc DB, không
 * {@code @Transactional} — bean không có proxy transaction nên ném lỗi ở đây không tự đánh dấu rollback-only.
 * Người gọi truyền role / chi nhánh của người bị tác động <b>đọc dưới khóa dòng</b> {@code accounts}.
 *
 * <table>
 *   <caption>Ai được làm gì</caption>
 *   <tr><th>Thao tác</th><th>ADMIN</th><th>SUPER_MANAGER</th><th>BRANCH_MANAGER</th></tr>
 *   <tr><td>CREATE (02 BR-QT-01)</td><td>SUPER_MANAGER</td><td>A05–A08</td>
 *       <td>A06–A08, chi nhánh mình ({@code branchId} null = chi nhánh mình, 00-method L40)</td></tr>
 *   <tr><td>CHANGE_ROLE (BR-QT-05)</td><td>—</td><td>A05–A08 → A05–A08</td><td>—</td></tr>
 *   <tr><td>TRANSFER (BR-QT-06)</td><td>—</td><td>A05–A08</td><td>—</td></tr>
 *   <tr><td>DISABLE, REACTIVATE (BR-QT-07, 10)</td><td>SUPER_MANAGER</td><td>A05–A08</td>
 *       <td>A06–A08 cùng chi nhánh</td></tr>
 *   <tr><td>RESEND_INITIAL_PASSWORD, CHANGE_EMAIL (BR-QT-02, BR-TK-16)</td><td>SUPER_MANAGER</td><td>A05–A08</td>
 *       <td>A06–A08 cùng chi nhánh</td></tr>
 * </table>
 *
 * Người bị tác động là chính mình luôn bị từ chối (BR-QT-07; BR-TK-15 cấm tự sửa email). Role ngoài A03–A05 đã bị
 * {@code @PreAuthorize} chặn ở controller (403 không audit, docs/adr/0003); policy vẫn từ chối để không phụ thuộc
 * controller. Lỗi 400 (BR-QT-03 chi nhánh không hợp lệ, BR-QT-04…) không thuộc policy: service kiểm sau policy.
 */
@Component
public class StaffHierarchyPolicy {

    /** A05–A08: nhân viên thuộc một chi nhánh (BR-QT-03). */
    static final Set<Role> BRANCH_STAFF = EnumSet.of(Role.BRANCH_MANAGER, Role.RECEPTIONIST, Role.VET, Role.CARETAKER);
    /** A06–A08: cấp dưới của BRANCH_MANAGER (BR-QT-01, 07). */
    static final Set<Role> BRANCH_SUBORDINATES = EnumSet.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER);

    /** Người thực hiện, lấy từ principal của request (role, chi nhánh đọc từ DB ở request này, docs/adr/0003). */
    public record Actor(Long accountId, Role role, Long branchId) {

        public static Actor of(SecurityPrincipal principal) {
            return new Actor(principal.accountId(), Role.valueOf(principal.role()), principal.branchId());
        }
    }

    /**
     * Một yêu cầu cần kiểm. {@code target*} là người bị tác động (không có với {@link StaffAction#CREATE});
     * {@code requested*} là role / chi nhánh muốn đặt (tạo, đổi chức vụ, điều chuyển, kích hoạt lại gán chi nhánh).
     */
    public record Request(StaffAction action, Long targetAccountId, Role targetRole, Long targetBranchId,
                          Role requestedRole, Long requestedBranchId) {

        public Request {
            Objects.requireNonNull(action, "action");
            if (action == StaffAction.CREATE) {
                Objects.requireNonNull(requestedRole, "requestedRole");
            } else {
                Objects.requireNonNull(targetAccountId, "targetAccountId");
                Objects.requireNonNull(targetRole, "targetRole");
            }
        }

        public static Request create(Role role, Long branchId) {
            return new Request(StaffAction.CREATE, null, null, null, role, branchId);
        }

        /** Vô hiệu hóa, gửi lại mật khẩu ban đầu, sửa email. */
        public static Request on(StaffAction action, Long targetAccountId, Role targetRole, Long targetBranchId) {
            return new Request(action, targetAccountId, targetRole, targetBranchId, null, null);
        }

        public static Request changeRole(Long targetAccountId, Role targetRole, Long targetBranchId, Role newRole) {
            return new Request(StaffAction.CHANGE_ROLE, targetAccountId, targetRole, targetBranchId, newRole, null);
        }

        public static Request transfer(Long targetAccountId, Role targetRole, Long targetBranchId, Long newBranchId) {
            return new Request(StaffAction.TRANSFER, targetAccountId, targetRole, targetBranchId, null, newBranchId);
        }

        /** {@code branchId} null = giữ chi nhánh cũ (BR-QT-10 "có thể gán lại chi nhánh"). */
        public static Request reactivate(Long targetAccountId, Role targetRole, Long targetBranchId, Long branchId) {
            return new Request(StaffAction.REACTIVATE, targetAccountId, targetRole, targetBranchId, null, branchId);
        }
    }

    /** Cho phép thì trả về; từ chối thì ném {@link StaffHierarchyDeniedException} mang sẵn bản ghi audit. */
    public void check(Actor actor, Request request) {
        if (!allows(actor, request)) {
            throw new StaffHierarchyDeniedException("STAFF_HIERARCHY " + request.action(),
                    String.valueOf(actor.role()), deniedAudit(request));
        }
    }

    boolean allows(Actor actor, Request request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        if (request.action() == StaffAction.CREATE) {
            Long branchId = actor.role() == Role.BRANCH_MANAGER && request.requestedBranchId() == null
                    ? actor.branchId() : request.requestedBranchId();
            return manages(actor, request.requestedRole(), branchId);
        }
        if (request.targetAccountId().equals(actor.accountId())) {
            return false;
        }
        return switch (request.action()) {
            case CHANGE_ROLE -> actor.role() == Role.SUPER_MANAGER
                    && BRANCH_STAFF.contains(request.targetRole())
                    && BRANCH_STAFF.contains(request.requestedRole());
            case TRANSFER -> actor.role() == Role.SUPER_MANAGER && BRANCH_STAFF.contains(request.targetRole());
            case REACTIVATE -> manages(actor, request.targetRole(), request.targetBranchId())
                    && (actor.role() != Role.BRANCH_MANAGER || request.requestedBranchId() == null
                        || request.requestedBranchId().equals(actor.branchId()));
            case DISABLE, RESEND_INITIAL_PASSWORD, CHANGE_EMAIL ->
                    manages(actor, request.targetRole(), request.targetBranchId());
            case CREATE -> throw new IllegalStateException("CREATE đã xử lý ở trên");
        };
    }

    /** Ô "ai quản lý ai" dùng chung cho tạo, vô hiệu hóa, kích hoạt lại, gửi lại mật khẩu, sửa email. */
    private static boolean manages(Actor actor, Role role, Long branchId) {
        return switch (actor.role()) {
            case ADMIN -> role == Role.SUPER_MANAGER;
            case SUPER_MANAGER -> BRANCH_STAFF.contains(role);
            case BRANCH_MANAGER -> BRANCH_SUBORDINATES.contains(role)
                    && actor.branchId() != null && actor.branchId().equals(branchId);
            default -> false;
        };
    }

    private static AuditEntry deniedAudit(Request request) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("action", request.action().name());
        after.put("targetRole", request.targetRole() == null ? null : request.targetRole().name());
        after.put("targetBranchId", request.targetBranchId());
        after.put("requestedRole", request.requestedRole() == null ? null : request.requestedRole().name());
        after.put("requestedBranchId", request.requestedBranchId());
        AuditEntry entry = AuditEntry.of(QtAuditActions.STAFF_ACTION_DENIED).after(after);
        return request.targetAccountId() == null ? entry : entry.entity("accounts", request.targetAccountId());
    }
}
