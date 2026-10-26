package com.petcare.module.visit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.RecordVaccinationRequest;
import com.petcare.module.visit.dto.RecordVaccinationResultResponse;
import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.service.VaccinationOptionService;
import com.petcare.module.visit.service.VaccinationRecordService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Tiêm chủng — visit-v1 #18 gợi ý mũi (docs/adr/0073), #19 ghi mũi (docs/adr/0074), #20 xóa mũi ghi nhầm
 * (docs/adr/0075) (UC49; A07 được gán lượt). Tham số thiếu / sai kiểu là 400 trước {@code @PreAuthorize}; mọi rule kiểm ở
 * service.
 */
@RestController
@RequestMapping("/api/visits/{visitId}")
public class VaccinationController {

    /** Hợp đồng #18: A07 (người được gán lượt kiểm ở service). */
    static final String VET = "hasRole('VET')";

    private final VaccinationOptionService options;
    private final VaccinationRecordService recording;

    public VaccinationController(VaccinationOptionService options, VaccinationRecordService recording) {
        this.options = options;
        this.recording = recording;
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

    @Operation(operationId = "recordVaccination", tags = "Vaccinations", summary = "Ghi nhận mũi tiêm",
            description = "UC49, ST06 · BR-KB-04, BR-KO-05, BR-TB-03. Actor: A07. Quyền: VET được gán lượt. Chuyển trạng "
                + "thái: IN_PROGRESS giữ nguyên · dòng Order VACCINE · trừ kho FEFO · Care Task#5. A6, A21, A22, A23. Thứ "
                + "tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 404 "
                + "loại vaccine → 400 BR-KB-04 → 400 BR-SP-07 → 400 BR-KB-04 (mũi, sản phẩm, tồn) (docs/adr/0074). Ngày "
                + "tiêm = hôm nay (A6). Chưa đủ tuổi: cảnh báo trong warnings, không chặn. Không audit.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-KB-04` loại vaccine không áp dụng cho loài của thú · mũi không thuộc "
            + "phác đồ đang dùng của loại và loài · sản phẩm không phải vaccine đang kinh doanh của loại đã chọn · sản "
            + "phẩm không còn tồn khả dụng · `BR-SP-07` loại vaccine đã ngừng sử dụng · `BR-KO-05` hết lô chưa hết hạn "
            + "khi đang ghi (A22) · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại")
    @ApiErrorDoc(status = 404, description = "Không có Visit, Visit nhóm GROOMING hoặc không có loại vaccine")
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS")
    @PostMapping("/vaccinations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(VET)
    public ApiResponse<RecordVaccinationResultResponse> recordVaccination(@PathVariable Long visitId,
            @Valid @RequestBody RecordVaccinationRequest request) {
        return ApiResponse.created(recording.record(visitId, request), "Đã ghi nhận mũi tiêm");
    }

    @Operation(operationId = "deleteVaccination", tags = "Vaccinations", summary = "Xóa mũi tiêm ghi nhầm",
            description = "UC49 · BR-KB-04, BR-TB-03. Actor: A07. Quyền: VET phụ trách hiện tại (A24). Chuyển trạng thái: "
                + "IN_PROGRESS giữ nguyên · hoàn kho đúng lô · xóa dòng Order VACCINE · mũi cũ mới nhất hết `superseded` "
                + "(A25). A24, A25, Q4. Thứ tự lỗi: 400 id → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → 403 "
                + "người phụ trách → 409 → 404 mũi (docs/adr/0075). Hoàn kho đúng lô (kể cả lô đã hết hạn), xóa dòng Order "
                + "vaccine trong cùng transaction. Không audit. Care Task đã hủy của mũi cũ không mở lại (Q4).",
            parameters = {@Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"),
                    @Parameter(name = "vaccinationId", in = ParameterIn.PATH, example = "701")})
    @ApiErrorDoc(status = 400, description = "`MALFORMED_REQUEST` id sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại (A24)")
    @ApiErrorDoc(status = 404, description = "Không có Visit, Visit nhóm GROOMING hoặc mũi tiêm không thuộc lượt này")
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS — sau COMPLETED, sai sót ghi bằng bản bổ sung")
    @DeleteMapping("/vaccinations/{vaccinationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(VET)
    public void deleteVaccination(@PathVariable Long visitId, @PathVariable Long vaccinationId) {
        recording.delete(visitId, vaccinationId);
    }
}
