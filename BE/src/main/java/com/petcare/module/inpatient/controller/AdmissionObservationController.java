package com.petcare.module.inpatient.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.inpatient.dto.CreateObservationRequest;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.service.AdmissionObservationService;
import com.petcare.module.inpatient.service.AdmissionReadService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Theo dõi nội trú (inpatient-v1 #9–#10; UC56, BR-LT-16; docs/adr/0149 mục 31–36). Không chứa logic và không biết mã
 * rule. {@code @PreAuthorize} là hàng rào vai trò <b>duy nhất</b>: {@code BranchScope} cho ADMIN / SUPER_MANAGER đi qua ở
 * phạm vi toàn chuỗi, nên thiếu annotation thì họ ghi được. Không đòi người phụ trách Visit (khác #5, #8).
 */
@RestController
@RequestMapping("/api")
public class AdmissionObservationController {

    /** #9: VET và CARETAKER ghi; A06 chỉ xem (inpatient-v1 §B). */
    static final String VET_OR_CARETAKER = "hasAnyRole('VET', 'CARETAKER')";
    /** #10: A06 chỉ xem, A07, A08; BRANCH_MANAGER chưa được xem (ADR-0148). */
    static final String STAFF = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";

    private final AdmissionObservationService observations;
    private final AdmissionReadService reads;

    public AdmissionObservationController(AdmissionObservationService observations, AdmissionReadService reads) {
        this.observations = observations;
        this.reads = reads;
    }

    /** inpatient-v1 #9. */
    @Operation(operationId = "createObservation", tags = "Observations", summary = "Ghi chỉ số theo dõi",
            description = "UC56 · BR-LT-16. Actor: A07, A08. Quyền: A07, A08 của chi nhánh. Chuyển trạng thái: —. "
                + "Chỉ thêm, không sửa / xóa. Mọi VET / CARETAKER của chi nhánh ghi được, không cần là người phụ "
                + "trách Visit. Có `weightKg` thì ghi thêm vào hồ sơ cân của thú (BR-KH-04) với nguồn `VET` gắn Visit "
                + "của ca. `abnormal = true` báo khách (kênh theo BR-TB-02) và VET phụ trách (IN_APP; không gửi cho "
                + "chính VET đó nếu họ là người ghi) bằng cặp mẫu `INPATIENT_OBSERVATION_ABNORMAL` / `_APP` "
                + "(BR-LT-16, ADR-0148). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 409 → 400 "
                + "`BR-LT-16` (ADR-0149).",
            parameters = @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"))
    @ApiErrorDoc(status = 400, description = "`BR-LT-16` không có chỉ số hay ghi chú nào (rỗng sau `strip()`) · giá "
            + "trị ngoài giới hạn lưu trữ (nhiệt độ, nhịp tim / nhịp thở 1…32767, cân nặng 0.01…9999.99 kg); "
            + "`VALIDATION_FAILED` thiếu `abnormal` hoặc ghi chú quá 500 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Không phải VET / CARETAKER · ca của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED`")
    @PostMapping("/admissions/{admissionId}/observations")
    @PreAuthorize(VET_OR_CARETAKER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ObservationResponse> create(@PathVariable Long admissionId,
            @Valid @RequestBody CreateObservationRequest request) {
        return ApiResponse.created(observations.create(admissionId, request), "Đã ghi chỉ số theo dõi");
    }

    /** inpatient-v1 #10. */
    @Operation(operationId = "listObservations", tags = "Observations", summary = "Chỉ số theo dõi của ca nội trú",
            description = "UC56 · BR-LT-16. Actor: A06, A07, A08. Quyền: A06 chỉ xem; A07; A08 theo BR-LT-15. Chuyển "
                + "trạng thái: —. Mới nhất trước (trùng thì `observationId` giảm dần). Đọc được cả ca đã xuất viện.",
            parameters = {
                    @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "Sai vai trò · ca của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @GetMapping("/admissions/{admissionId}/observations")
    @PreAuthorize(STAFF)
    public ApiResponse<PageResponse<ObservationResponse>> list(@PathVariable Long admissionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reads.listObservations(admissionId, page, size), "Chỉ số theo dõi của ca nội trú");
    }
}
