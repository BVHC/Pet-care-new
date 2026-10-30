package com.petcare.module.report.dto;

import com.petcare.module.catalog.api.ServiceGroup;
import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code ServiceCountRow}: Số lượt một dịch vụ hoàn tất tại một chi nhánh (BR-BC-03 (2)). */
public record ServiceCountRow(
        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,
        @Schema(description = "Nhóm dịch vụ", example = "MEDICAL")
        ServiceGroup serviceGroup,
        @Schema(description = "Mã dịch vụ", example = "9")
        Long serviceId,
        @Schema(description = "Tên dịch vụ", example = "Khám tổng quát")
        String serviceName,
        @Schema(description = "Số lượt hoàn tất", example = "27")
        int count) {
}
