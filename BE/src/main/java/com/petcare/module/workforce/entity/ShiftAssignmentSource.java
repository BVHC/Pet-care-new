package com.petcare.module.workforce.entity;

/**
 * {@code shift_assignments.source} (V61, ADR-0123): {@code SELF} do nhân viên tự đăng ký theo tuần (BR-NS-11),
 * {@code MANAGER} do BRANCH_MANAGER phân hộ (BR-NS-02).
 */
public enum ShiftAssignmentSource {
    SELF, MANAGER
}
