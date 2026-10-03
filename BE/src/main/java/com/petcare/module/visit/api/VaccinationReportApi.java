package com.petcare.module.visit.api;

import java.time.LocalDate;
import java.util.List;

/** Owner: visit (KB) · BE-1. Caller: report (UC89). {@code branchId} null = toàn chuỗi. */
public interface VaccinationReportApi {

    record ReturnRateRow(Long branchId, Long vaccineTypeId, int dueCount, int returnedCount) {}

    /**
     * BR-BC-04: mẫu số = mũi có {@code next_due_date} trong kỳ, trừ thú mất trước ngày đó; tử số = mũi đã được
     * tiêm lại cùng loại vaccine không muộn quá 7 ngày [CFG]. Tính cho chi nhánh của mũi trước.
     */
    List<ReturnRateRow> revaccinationReturn(Long branchId, LocalDate from, LocalDate to);
}
