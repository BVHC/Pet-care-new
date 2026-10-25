package com.petcare.module.visit.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Gợi ý mũi và sản phẩm vaccine cho VET phụ trách lượt (visit-v1 #18, schema {@code VaccinationOptions}, UC49;
 * docs/adr/0073). Danh sách không bao giờ {@code null}; {@code suggestedProtocolId} / {@code lastDose} có thể
 * {@code null}. Chỉ dựng bởi {@code VaccinationOptionsMapper}.
 */
public record VaccinationOptionsResponse(
        @Schema(description = "Loại vaccine đã chọn", example = "5")
        Long vaccineTypeId,

        @Schema(description = "Các mũi đang dùng trong phác đồ của loài, theo mũi tăng dần")
        List<ProtocolDoseOptionResponse> protocol,

        @Schema(description = "Gợi ý theo lịch sử tiêm cùng loại tại hệ thống, chỉ tham khảo (BR-KB-04, A18)",
                example = "13")
        Long suggestedProtocolId,

        @Schema(description = "Mũi gần nhất cùng loại tại hệ thống; null nếu chưa tiêm (A20)")
        VaccinationRecordResponse lastDose,

        @Schema(description = "Chỉ sản phẩm thuộc loại còn tồn khả dụng tại chi nhánh")
        List<VaccineProductOptionResponse> products,

        @Schema(description = "Thú chưa đủ tuổi tối thiểu của mũi gợi ý — cảnh báo, không chặn", example = "false")
        boolean underMinAge) {
}
