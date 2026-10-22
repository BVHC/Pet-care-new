package com.petcare.module.identity.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.dto.LockResult;
import com.petcare.module.identity.dto.ReasonRequest;
import com.petcare.module.identity.service.AccountAdminQueryService;
import com.petcare.module.identity.service.AccountLockService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * {@code /api/accounts/**} của identity-v1 — tìm, khóa, mở khóa tài khoản (UC09, identity-v1 #28, #29, #30; BR-QT-11, 12;
 * docs/adr/0035). Chỉ ADMIN (role khác → 403 {@code ACCESS_DENIED}, không audit — khóa không đi qua ma trận phân cấp,
 * docs/adr/0029). Bị chặn khi ADMIN còn phải đổi mật khẩu lần đầu (BR-TK-17).
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountAdminController {

    static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final AccountAdminQueryService accountQueries;
    private final AccountLockService accountLocks;

    public AccountAdminController(AccountAdminQueryService accountQueries, AccountLockService accountLocks) {
        this.accountQueries = accountQueries;
        this.accountLocks = accountLocks;
    }

    /** identity-v1 #28. {@code q} tìm trong email; 255 = {@code accounts.email} (erd §1). */
    @Operation(operationId = "listAccounts", tags = "Accounts",
            summary = "Tìm tài khoản (khách và nhân viên)",
            description = "UC09 · BR-QT-11. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "q", in = ParameterIn.QUERY, description = "Tìm theo email", example = "nguyen"),
                    @Parameter(name = "role", in = ParameterIn.QUERY, example = "VET"),
                    @Parameter(name = "isLocked", in = ParameterIn.QUERY, example = "false"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<PageResponse<AccountSummary>> list(
            @RequestParam(required = false) @Size(max = 255, message = "Từ khóa tìm tối đa 255 ký tự") String q,
            @RequestParam(required = false) Role role,
            @RequestParam(name = "isLocked", required = false) Boolean isLocked,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(accountQueries.listAccounts(q, role, isLocked, page, size));
    }

    /**
     * identity-v1 #29 (Tài khoản#7). Hủy mọi phiên của tài khoản, trả danh sách quy trình dở dang; đã khóa → 409, tự khóa
     * → 400 BR-QT-11.
     */
    @Operation(operationId = "lockAccount", tags = "Accounts",
            summary = "Khóa tài khoản",
            description = "UC09 · BR-QT-04, 11, 12, BR-TN-08, BR-TK-11. Actor: A03. Quyền: Chỉ ADMIN; "
                + "không khóa chính mình. Chuyển trạng thái: is_locked false → true (Tài khoản#7). Mọi role, mọi trạng "
                + "thái (kể cả PENDING, DISABLED) trừ chính mình; status giữ nguyên (BR-QT-12). Hủy mọi phiên; "
                + "pendingWork sắp theo thứ tự enum rồi entityId (Visit IN_PROGRESS → needsReassign); phát "
                + "AccountLockedEvent; thông báo trong app theo BR-QT-11 (nhân viên A06–A08: BRANCH_MANAGER chi nhánh, "
                + "không còn thì SUPER_MANAGER; BRANCH_MANAGER: SUPER_MANAGER, quản lý cuối của chi nhánh ACTIVE → "
                + "LAST_BRANCH_MANAGER_LOCKED_APP; khách: lễ tân các chi nhánh có việc dở — 06 Q2); ghi audit kèm lý "
                + "do (docs/adr/0035).",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42")})
    @ApiErrorDoc(status = 400, description = "Thiếu lý do (`VALIDATION_FAILED`) · `BR-QT-11` khóa chính "
            + "mình")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` đã bị khóa · "
            + "`CONCURRENCY_CONFLICT` chức vụ / chi nhánh của người bị khóa vừa đổi")
    @PostMapping("/{id}/lock")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<LockResult> lock(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(accountLocks.lockAccount(id, request.reason()), "Đã khóa tài khoản");
    }

    /** identity-v1 #30 (Tài khoản#8). {@code status} giữ nguyên giá trị trước khi khóa; chưa khóa → 409. */
    @Operation(operationId = "unlockAccount", tags = "Accounts",
            summary = "Mở khóa tài khoản",
            description = "UC09 · BR-QT-11, 12. Actor: A03. Quyền: Chỉ ADMIN. Chuyển trạng thái: is_locked "
                + "true → false (Tài khoản#8). status giữ nguyên giá trị trước khi khóa (BR-QT-12); giữ lý do khóa gần "
                + "nhất; không mở phiên, không thông báo; ghi audit kèm lý do (docs/adr/0035).",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42")})
    @ApiErrorDoc(status = 400, description = "Thiếu lý do (`VALIDATION_FAILED`)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` không bị khóa")
    @PostMapping("/{id}/unlock")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<AccountSummary> unlock(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(accountLocks.unlockAccount(id, request.reason()), "Đã mở khóa tài khoản");
    }
}
