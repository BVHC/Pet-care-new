package com.petcare.module.identity.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.AccountSummary;
import com.petcare.module.identity.mapper.AccountAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.platform.model.PageResponse;

/**
 * identity-v1 #28 — ADMIN tìm tài khoản để khóa / mở khóa (UC09, BR-QT-11; docs/adr/0035). Mọi role, mọi trạng thái;
 * {@code q} tìm trong email, không phân biệt hoa thường. Đọc projection, không SELECT cột bí mật. Chỉ ADMIN — kiểm bằng
 * {@code @PreAuthorize} ở controller; tài khoản không có {@code branch_id} nên không qua {@code BranchScope}.
 */
@Service
@Transactional(readOnly = true)
public class AccountAdminQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private static final List<Boolean> ANY_LOCK = List.of(Boolean.TRUE, Boolean.FALSE);

    private final AccountRepository accounts;
    private final AccountAdminMapper mapper;

    public AccountAdminQueryService(AccountRepository accounts, AccountAdminMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    /** {@code page} ≥ 0, {@code size} 1–100 (kẹp như {@code StaffQueryService}, docs/api/00-method.md §3.4). */
    public PageResponse<AccountSummary> listAccounts(String q, Role role, Boolean isLocked, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Set<Role> roles = role == null ? EnumSet.allOf(Role.class) : EnumSet.of(role);
        List<Boolean> lockedValues = isLocked == null ? ANY_LOCK : List.of(isLocked);
        return PageResponse.of(accounts.findAccountPage(roles, lockedValues, StaffQueryService.likePattern(q), pageable)
                .map(mapper::toAccountSummary));
    }
}
