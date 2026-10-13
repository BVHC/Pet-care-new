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
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

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
    @PostMapping("/{id}/lock")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<LockResult> lock(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(accountLocks.lockAccount(id, request.reason()), "Đã khóa tài khoản");
    }

    /** identity-v1 #30 (Tài khoản#8). {@code status} giữ nguyên giá trị trước khi khóa; chưa khóa → 409. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<AccountSummary> unlock(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(accountLocks.unlockAccount(id, request.reason()), "Đã mở khóa tài khoản");
    }
}
