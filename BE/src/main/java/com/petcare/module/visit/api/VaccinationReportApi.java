package com.petcare.module.visit.api;

import java.time.LocalDate;
import java.util.List;

/** Owner: visit (KB) · BE-1. Caller: report (UC89). {@code branchId} null = toàn chuỗi. */
public interface VaccinationReportApi {

    /**
     * {@code pendingCount}: số mũi TRONG mẫu số mà CHƯA quay lại (không tồn tại mũi tiêm lại thỏa điều kiện tử số) VÀ
     * {@code next_due_date} + grace {@code >= today}, tức bao gồm cả ngày cuối của grace; mũi chưa tới
     * {@code next_due_date} cũng tính. Chỉ để minh bạch (docs/adr/0087 G11, ADR-0090 Proposed): không đổi
     * {@code dueCount}, {@code returnedCount} hay tỷ lệ.
     */
    record ReturnRateRow(Long branchId, Long vaccineTypeId, int dueCount, int returnedCount, int pendingCount) {}

    /**
     * BR-BC-04: mẫu số = mũi có {@code next_due_date} trong kỳ, trừ thú mất trước ngày đó; tử số = mũi đã được
     * tiêm lại cùng loại vaccine không muộn quá 7 ngày [CFG]. Tính cho chi nhánh của mũi trước. {@code today} là
     * ngày hiện tại theo giờ Việt Nam do bên gọi truyền (không dùng giờ DB), chỉ dùng cho {@code pendingCount}.
     */
    List<ReturnRateRow> revaccinationReturn(Long branchId, LocalDate from, LocalDate to, LocalDate today);
}
