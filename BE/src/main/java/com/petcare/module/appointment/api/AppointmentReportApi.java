package com.petcare.module.appointment.api;

import java.time.LocalDate;
import java.util.List;

/** Owner: appointment (LH) · BE-1. Caller: report (UC89). {@code branchId} null = toàn chuỗi. */
public interface AppointmentReportApi {

    /** {@code dueCount} = lịch có khung giờ trong kỳ (lịch đến hạn), không tính lịch hủy do phòng khám. */
    record NoShowRow(Long branchId, int dueCount, int noShowCount, int lateCancelCount) {}

    /** BR-BC-03 (3): tỷ lệ NO_SHOW và hủy muộn trên tổng lịch hẹn đến hạn trong kỳ. */
    List<NoShowRow> noShowAndLateCancel(Long branchId, LocalDate from, LocalDate to);
}
