package com.petcare.module.content.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Owner: content (DG) · BE-2. Caller: report (UC89). */
public interface FeedbackReportApi {

    /** {@code branchId} null = feedback không gắn chi nhánh; {@code averageRating} chỉ tính feedback có chấm điểm. */
    record FeedbackStatRow(Long branchId, int count, BigDecimal averageRating) {}

    /**
     * BR-BC-03 (6). {@code branchId} null = toàn chuỗi, gồm cả feedback không gắn chi nhánh (chỉ SUPER_MANAGER,
     * BR-DG-03); khác null = chỉ chi nhánh đó.
     */
    List<FeedbackStatRow> feedbackStats(Long branchId, LocalDate from, LocalDate to);
}
