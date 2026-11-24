package com.petcare.module.workforce.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Đăng ký ca theo tuần (workforce-v1 {@code PutShiftRegistrationRequest}, #15): toàn bộ (ngày, ca) nhân viên muốn có trong
 * tuần, thay các phân ca {@code SELF} hiện có (BR-NS-11). Không có {@code staffId} / {@code branchId} / {@code source}:
 * luôn là người gọi, chi nhánh của họ (đọc dưới khóa) và {@code SELF}. {@code items} rỗng hợp lệ về hình thức (rồi bị
 * BR-NS-12 chặn); tối đa 100 phần tử (ADR-0148 mục 12).
 */
public record PutShiftRegistrationRequest(
        @ArraySchema(arraySchema = @Schema(description = "Toàn bộ (ngày, ca) nhân viên muốn có trong tuần; thay thế các "
                + "phân ca SELF hiện có (BR-NS-11)", requiredMode = Schema.RequiredMode.REQUIRED), maxItems = 100)
        @NotNull(message = "Vui lòng gửi danh sách ca đăng ký")
        @Size(max = 100, message = "Tối đa 100 mục mỗi lần đăng ký")
        List<@NotNull(message = "Mục đăng ký không được để trống") @Valid ShiftRegistrationItem> items) {
}
