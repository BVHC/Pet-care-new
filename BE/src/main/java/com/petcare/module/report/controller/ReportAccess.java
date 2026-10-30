package com.petcare.module.report.controller;

/** Biểu thức {@code @PreAuthorize} của report-v1: SUPER_MANAGER (A04) và BRANCH_MANAGER (A05); ADMIN không xem (A03 chỉ kỹ thuật). */
final class ReportAccess {

    static final String MANAGERS = "hasAnyRole('SUPER_MANAGER', 'BRANCH_MANAGER')";

    private ReportAccess() {
    }
}
