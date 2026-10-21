package com.petcare.module.visit.dto;

import java.time.Instant;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.VisitStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một lượt tiếp nhận (visit-v1 schema {@code Visit}, UC44–46). Chỉ dựng bởi {@code VisitMapper}; các phần sau (hàng
 * đợi, gán, gọi, hủy) dùng lại. Không lộ {@code checked_in_by}, {@code created_at}, {@code updated_at}, SĐT / email
 * của khách. {@code orderId} là Order nguồn VISIT của lượt ({@code orders.visit_id}), không phải cột của
 * {@code visits}. Mọi trường luôn có mặt trong JSON; chưa có giá trị thì {@code null} (docs/adr/0059).
 */
public record VisitResponse(
        @Schema(description = "Mã lượt", example = "9001")
        Long visitId,

        @Schema(description = "Mã hiển thị", example = "TN-2610-00042")
        String code,

        @Schema(description = "Chi nhánh tiếp nhận", example = "3")
        Long branchId,

        @Schema(description = "Chủ tại thời điểm tiếp nhận", example = "88")
        Long customerId,

        @Schema(description = "Họ tên chủ tại thời điểm tiếp nhận", example = "Nguyễn Văn An")
        String customerName,

        @Schema(description = "Thú cưng", example = "12")
        Long petId,

        @Schema(description = "Tên thú cưng", example = "Milu")
        String petName,

        @Schema(description = "Loài", example = "DOG")
        Species species,

        @Schema(description = "Lịch hẹn; null = walk-in", example = "501")
        Long appointmentId,

        @Schema(description = "Nhóm dịch vụ, quyết định chức vụ được gán (BR-TN-05)", example = "MEDICAL",
                allowableValues = {"MEDICAL", "GROOMING"})
        ServiceGroup serviceGroup,

        @Schema(description = "Trạng thái (03 #4)", example = "WAITING")
        VisitStatus status,

        @Schema(description = "Hạng ưu tiên trong hàng đợi (BR-TN-03, 04); lễ tân sắp lại / xác định cấp cứu có thể đổi "
                + "(A2, A3) — có lịch hẹn hay không xem appointmentId", example = "APPOINTMENT")
        PriorityClass priorityClass,

        @Schema(description = "Giờ hẹn (lịch hẹn giữ ưu tiên) hoặc giờ check-in", example = "2026-10-20T02:00:00Z")
        Instant queueSortKey,

        @Schema(description = "Nhân viên phụ trách; null khi chưa gán", example = "45")
        Long assigneeId,

        @Schema(description = "Tên nhân viên phụ trách; null khi chưa gán", example = "Trần Bình")
        String assigneeName,

        @Schema(description = "Người phụ trách bị khóa (BR-QT-11, BR-TN-08)", example = "false")
        boolean needsReassign,

        @Schema(description = "Order nguồn VISIT của lượt", example = "7001")
        Long orderId,

        @Schema(description = "Thời điểm tiếp nhận", example = "2026-10-20T01:55:00Z")
        Instant checkedInAt,

        @Schema(description = "Thời điểm gọi lượt; null nếu chưa gọi", example = "2026-10-20T02:05:00Z")
        Instant calledAt,

        @Schema(description = "Thời điểm hoàn tất; null nếu chưa", example = "2026-10-20T02:40:00Z")
        Instant completedAt,

        @Schema(description = "Thời điểm hủy; null nếu chưa", example = "2026-10-20T02:10:00Z")
        Instant cancelledAt,

        @Schema(description = "Lý do hủy; null nếu không có", example = "Khách bận việc")
        String cancelReason) {
}
