package com.petcare.module.sales.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Người nhận thông báo ST13 của một chi nhánh (BR-TG-05, BR-BH-05; docs/adr/0064): mọi BRANCH_MANAGER đang hoạt động;
 * chi nhánh không còn ai thì mọi SUPER_MANAGER đang hoạt động (cùng cách ADR-0035), để ca bị kẹt vẫn có người chịu trách
 * nhiệm. Chỉ đọc, chạy trong transaction của bên gọi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftNoticeRecipients {

    private final StaffDirectoryApi staffDirectory;

    public List<Long> forBranch(Long branchId) {
        List<Long> managers = staffDirectory.findActiveStaffIds(branchId, Role.BRANCH_MANAGER);
        if (!managers.isEmpty()) {
            return managers;
        }
        List<Long> superManagers = staffDirectory.findActiveSuperManagerIds();
        log.warn("ST13_NOTICE_FALLBACK_SUPER_MANAGER branchId={} recipients={}", branchId, superManagers.size());
        return superManagers;
    }
}
