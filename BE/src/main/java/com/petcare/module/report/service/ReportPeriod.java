package com.petcare.module.report.service;

import java.time.LocalDate;

import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Kiểm kỳ báo cáo (BR-BC-01; docs/adr/0087 G2): {@code from ≤ to} và {@code to} nhỏ hơn {@code from + N tháng}
 * ({@code N} = [CFG] {@code report.max_period_months}), nghĩa là 01/01–31/12 hợp lệ còn 01/01–01/01 năm sau thì không.
 */
final class ReportPeriod {

    static final String RULE = "BR-BC-01";

    private ReportPeriod() {
    }

    static void validate(LocalDate from, LocalDate to, int maxMonths) {
        if (from.isAfter(to)) {
            throw new BusinessRuleViolationException(RULE, "Kỳ báo cáo không hợp lệ: ngày bắt đầu sau ngày kết thúc");
        }
        if (!to.isBefore(from.plusMonths(maxMonths))) {
            throw new BusinessRuleViolationException(RULE, "Kỳ báo cáo dài tối đa " + maxMonths + " tháng");
        }
    }
}
