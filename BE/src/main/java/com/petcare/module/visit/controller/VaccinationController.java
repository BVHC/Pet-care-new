package com.petcare.module.visit.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.service.VaccinationOptionService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * Tiêm chủng — visit-v1 #18 gợi ý mũi (UC49; A07 được gán lượt; docs/adr/0073). #19 ghi, #20 xóa mũi sẽ thêm vào
 * đây. Tham số thiếu / sai kiểu là 400 trước {@code @PreAuthorize}; mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/visits/{visitId}")
public class VaccinationController {

    /** Hợp đồng #18: A07 (người được gán lượt kiểm ở service). */
    static final String VET = "hasRole('VET')";

    private final VaccinationOptionService options;

    public VaccinationController(VaccinationOptionService options) {
        this.options = options;
    }

    @Operation(operationId = "getVaccinationOptions", tags = "Vaccinations",
            summary = "Gợi ý mũi và sản phẩm vaccine",
            description = "UC49 · BR-KB-04, BR-SP-02, 07, BR-KO-01. Actor: A07. Quyền: VET được gán lượt. Chuyển trạng thái: "
                + "—. A18, A19, A20. Thứ tự lỗi: 400 tham số → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 "
                + "người phụ trách → 404 loại vaccine → 400 BR-KB-04 → 400 BR-SP-07 (docs/adr/0073). Chỉ đọc, không khóa.",
            parameters = {
                    @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"),
                    @Parameter(name = "vaccineTypeId", in = ParameterIn.QUERY, required = true,
                            description = "Loại vaccine đúng loài của thú", example = "5")})
    @ApiErrorDoc(status = 400, description = "`BR-KB-04` loại vaccine không áp dụng cho loài của thú · `BR-SP-07` loại "
            + "vaccine đã ngừng sử dụng · thiếu / sai kiểu `vaccineTypeId` (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại")
    @ApiErrorDoc(status = 404, description = "Không có Visit, Visit nhóm GROOMING hoặc không có loại vaccine")
    @GetMapping("/vaccination-options")
    @PreAuthorize(VET)
    public ApiResponse<VaccinationOptionsResponse> getVaccinationOptions(@PathVariable Long visitId,
            @RequestParam Long vaccineTypeId) {
        return ApiResponse.ok(options.getOptions(visitId, vaccineTypeId), "Gợi ý mũi tiêm");
    }
}
