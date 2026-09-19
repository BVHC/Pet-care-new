package com.petcare.module.auth.service;

import com.petcare.platform.enums.AccountStatus;

import java.util.UUID;

/**
 * Ranh giới cho module IAM (02) — không lộ entity {@code Account} ra ngoài
 * module Auth (docs/convention/backend/01-package-structure.md).
 * <p>
 * {@code previousStatus} phục vụ {@code audit_logs.snapshot_before} (RULE-25-01
 * `previous_state`) — {@link com.petcare.platform.audit.AuditAspect} dò accessor
 * {@code previousStatus()} trên giá trị trả về của method {@code @Auditable}
 * sau khi {@code proceed()} xong. Constructor 2-tham số (giữ tương thích các
 * call site chỉ đọc trạng thái hiện tại, vd {@code getSummary}) coi như
 * "không có transition" — {@code previousStatus == status}.
 */
public record AccountSummary(UUID accountId, AccountStatus previousStatus, AccountStatus status) {

    public AccountSummary(UUID accountId, AccountStatus status) {
        this(accountId, status, status);
    }
}
