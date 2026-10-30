package com.petcare.module.inventory.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Người nhận cảnh báo ST08 của một chi nhánh (BR-KO-07; docs/adr/0087): mọi BRANCH_MANAGER đang hoạt động; chi nhánh
 * không còn ai thì mọi SUPER_MANAGER đang hoạt động (cùng cách ST13, ADR-0064). Chỉ đọc, chạy trong transaction của bên
 * gọi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockAlertRecipients {

    private final StaffDirectoryApi staffDirectory;

    public List<Long> forBranch(Long branchId) {
        List<Long> managers = staffDirectory.findActiveStaffIds(branchId, Role.BRANCH_MANAGER);
        if (!managers.isEmpty()) {
            return managers;
        }
        List<Long> superManagers = staffDirectory.findActiveSuperManagerIds();
        log.warn("ST08_NOTICE_FALLBACK_SUPER_MANAGER branchId={} recipients={}", branchId, superManagers.size());
        return superManagers;
    }
}
