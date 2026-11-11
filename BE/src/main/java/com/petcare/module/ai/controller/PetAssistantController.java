package com.petcare.module.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.dto.RecommendationResponse;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.ai.service.RecommendationService;
import com.petcare.module.ai.service.VaccinationSummaryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.BranchScope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Trợ lý theo thú của khách — ai-v1 #3 (UC91, BR-AI-11; docs/adr/0132) và #4 (UC92, BR-AI-18…23; docs/adr/0133). Khách lấy
 * từ token; {@code petId} chỉ từ đường dẫn. Thú của người khác hoặc chủ cũ là 404 (kiểm lại mỗi lần gọi), thú đã mất là 400
 * {@code BR-KH-05}. Không chứa logic: {@link VaccinationSummaryService} và {@link RecommendationService} dựng kết quả.
 * {@code limit} ngoài 1–12 do Spring MVC kiểm ({@code HandlerMethodValidationException} → 400 {@code VALIDATION_FAILED},
 * không đặt {@code @Validated} ở lớp). Bean Validation (kiểu của {@code petId}) chạy trước {@code @PreAuthorize}
 * (docs/adr/0028).
 */
@RestController
@RequestMapping("/api")
public class PetAssistantController {

    private static final String OWNER_ONLY = "hasRole('CUSTOMER')";

    private final VaccinationSummaryService vaccinations;
    private final RecommendationService recommendations;
    private final BranchScope scope;

    public PetAssistantController(VaccinationSummaryService vaccinations, RecommendationService recommendations,
            BranchScope scope) {
        this.vaccinations = vaccinations;
        this.recommendations = recommendations;
        this.scope = scope;
    }

    /** ai-v1 #3. */
    @Operation(operationId = "getPetVaccinationSummary", tags = "Pet assistant", summary = "Tra lịch tiêm của thú",
            description = "UC91 · BR-AI-11, 12. Actor: A02. Quyền: CUSTOMER, chỉ thú hiện thuộc về mình. Chuyển trạng thái: —. "
                + "Tất định, không qua LLM, chỉ mũi tiêm ghi nhận tại hệ thống (04 nguyên tắc 9); mỗi loại vaccine lấy mũi "
                + "mới nhất chưa bị thay thế. Không ghi nội dung vào nhật ký. Giao diện gọi sau `NEED_PET_CHOICE` hoặc khi "
                + "đã biết thú.",
            parameters = {@Parameter(name = "petId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400, description = "`BR-KH-05` thú đã mất · `MALFORMED_REQUEST` `petId` sai kiểu")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy thú — cả khi thú thuộc người khác hoặc chủ cũ (ADR-0006)")
    @GetMapping("/me/ai/pets/{petId}/vaccination-summary")
    @PreAuthorize(OWNER_ONLY)
    public ApiResponse<VaccinationSummary> getPetVaccinationSummary(@PathVariable Long petId) {
        return ApiResponse.ok(vaccinations.summarizeForAccount(scope.current().accountId(), petId));
    }

    /** ai-v1 #4. */
    @Operation(operationId = "getPetRecommendations", tags = "Pet assistant", summary = "Gợi ý cá nhân hóa cho thú",
            description = "UC92 · BR-AI-18…23. Actor: A02. Quyền: CUSTOMER, chỉ thú hiện thuộc về mình. Chuyển trạng thái: —. "
                + "Không bao giờ có thuốc, vaccine, sản phẩm kê đơn, dịch vụ khám / tiêm (BR-SP-01, BR-AI-18). Mỗi lần tính "
                + "từ dữ liệu thú hiện tại (BR-AI-23).",
            parameters = {
                    @Parameter(name = "petId", in = ParameterIn.PATH, example = "12"),
                    @Parameter(name = "group", in = ParameterIn.QUERY, example = "GROOMING",
                            description = "Vắng = cả ba nhóm"),
                    @Parameter(name = "limit", in = ParameterIn.QUERY, example = "6",
                            description = "1–12, vắng = 6 mỗi nhóm [CFG]")})
    @ApiErrorDoc(status = 400, description = "`BR-KH-05` thú đã mất · `VALIDATION_FAILED` `limit` ngoài 1–12")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy thú (kể cả thú của người khác)")
    @GetMapping("/me/ai/pets/{petId}/recommendations")
    @PreAuthorize(OWNER_ONLY)
    public ApiResponse<RecommendationResponse> getPetRecommendations(@PathVariable Long petId,
            @RequestParam(required = false) RecoGroup group,
            @RequestParam(required = false) @Min(value = 1, message = "limit phải từ 1 tới 12")
            @Max(value = 12, message = "limit phải từ 1 tới 12") Integer limit) {
        return ApiResponse.ok(recommendations.recommend(scope.current().accountId(), petId, group, limit));
    }
}
