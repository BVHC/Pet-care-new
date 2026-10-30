package com.petcare.module.content.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.content.api.FeedbackReportApi;
import com.petcare.module.content.repository.FeedbackReportRepository;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;

/**
 * Báo cáo feedback theo chi nhánh (BR-BC-03 (6); docs/adr/0087). Chỉ đọc, tham gia transaction của bên gọi. Kỳ
 * {@code [from, to]} (ngày VN, cả hai đầu) → nửa mở {@code [from 00:00, to + 1 00:00)}. Phạm vi (chi nhánh nào được xem,
 * nhóm không gắn chi nhánh chỉ SUPER_MANAGER) do module report kiểm trước khi gọi.
 */
@Service
@RequiredArgsConstructor
public class FeedbackReportService implements FeedbackReportApi {

    private final FeedbackReportRepository reports;

    @Override
    public List<FeedbackStatRow> feedbackStats(Long branchId, LocalDate from, LocalDate to) {
        return reports.stats(branchId, from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
    }
}
