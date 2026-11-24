package com.petcare.module.inpatient.controller;

import java.util.List;

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

import com.petcare.module.inpatient.dto.AdmissionOrderReasonRequest;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.dto.CompleteAdmissionOrderRequest;
import com.petcare.module.inpatient.dto.CreateAdmissionOrderRequest;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.service.AdmissionOrderService;
import com.petcare.module.inpatient.service.AdmissionReadService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Y lệnh nội trú (inpatient-v1 #5–#8; UC56; docs/adr/0149 mục 26–30). Không chứa logic và không biết mã rule.
 * {@code @PreAuthorize} là hàng rào vai trò <b>duy nhất</b>: {@code BranchScope} cho ADMIN / SUPER_MANAGER đi qua ở phạm vi
 * toàn chuỗi, nên thiếu annotation thì họ ghi được. Người phụ trách Visit (#5, #8) và loại việc của CARETAKER (#7) do
 * service kiểm.
 */
@RestController
@RequestMapping("/api")
public class AdmissionOrderController {

    /** #5, #8: chỉ VET (đúng người phụ trách do service kiểm); #7 thêm CARETAKER (không phải thuốc, do service kiểm). */
    static final String VET = "hasRole('VET')";
    static final String VET_OR_CARETAKER = "hasAnyRole('VET', 'CARETAKER')";
    /** #6: A06 chỉ xem, A07, A08 (inpatient-v1 §B); BRANCH_MANAGER chưa được xem (ADR-0148). */
    static final String STAFF = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";

    private final AdmissionOrderService orders;
    private final AdmissionReadService reads;

    public AdmissionOrderController(AdmissionOrderService orders, AdmissionReadService reads) {
        this.orders = orders;
        this.reads = reads;
    }

    /** inpatient-v1 #5. */
    @Operation(operationId = "createAdmissionOrder", tags = "Admission orders", summary = "Ra y lệnh",
            description = "UC56 · BR-LT-15. Actor: A07. Quyền: VET của chi nhánh. Chuyển trạng thái: — → ORDERED (Y "
                + "lệnh nội trú#1). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách "
                + "→ 409 → 400 `BR-LT-15`.",
            parameters = @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"))
    @ApiErrorDoc(status = 400, description = "`BR-LT-15` `MEDICATION` thiếu dòng đơn thuốc · dòng đơn không thuộc Visit "
            + "của ca · dòng đơn mua ngoài · loại khác `MEDICATION` mà có dòng đơn · nội dung rỗng sau `strip()`; "
            + "`VALIDATION_FAILED` thiếu `orderType` / `content` / `scheduledAt`, `content` quá 500 ký tự hoặc "
            + "`prescriptionItemId` ≤ 0; `MALFORMED_REQUEST` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED`")
    @PostMapping("/admissions/{admissionId}/orders")
    @PreAuthorize(VET)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdmissionOrderResponse> create(@PathVariable Long admissionId,
            @Valid @RequestBody CreateAdmissionOrderRequest request) {
        return ApiResponse.created(orders.create(admissionId, request), "Đã ra y lệnh");
    }

    /** inpatient-v1 #6. */
    @Operation(operationId = "listAdmissionOrders", tags = "Admission orders", summary = "Y lệnh của ca nội trú",
            description = "UC56 · BR-LT-15. Actor: A06, A07, A08. Quyền: A06 chỉ xem; A07; A08 theo BR-LT-15. Chuyển "
                + "trạng thái: —. Không phân trang. Sắp `scheduledAt` tăng dần, trùng thì `orderId` tăng dần.",
            parameters = {
                    @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "ORDERED")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "Sai vai trò · ca của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @GetMapping("/admissions/{admissionId}/orders")
    @PreAuthorize(STAFF)
    public ApiResponse<List<AdmissionOrderResponse>> list(@PathVariable Long admissionId,
            @RequestParam(required = false) AdmissionOrderStatus status) {
        return ApiResponse.ok(reads.listOrders(admissionId, status), "Y lệnh của ca nội trú");
    }

    /** inpatient-v1 #7. */
    @Operation(operationId = "completeAdmissionOrder", tags = "Admission orders",
            summary = "Đánh dấu đã thực hiện y lệnh",
            description = "UC56 · BR-LT-15. Actor: A07, A08. Quyền: A07; A08 chỉ việc không phải thuốc. Chuyển trạng "
                + "thái: ORDERED → DONE (Y lệnh nội trú#2). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh "
                + "→ 403 A08 với y lệnh thuốc → 409 → 400 `BR-LT-15`.",
            parameters = @Parameter(name = "admissionOrderId", in = ParameterIn.PATH, example = "88"))
    @ApiErrorDoc(status = 400, description = "`BR-LT-15` ghi chú rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc "
            + "quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · y lệnh của chi nhánh khác · A08 không thực hiện y lệnh thuốc")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy y lệnh")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25)")
    @PostMapping("/admission-orders/{admissionOrderId}/complete")
    @PreAuthorize(VET_OR_CARETAKER)
    public ApiResponse<AdmissionOrderResponse> complete(@PathVariable Long admissionOrderId,
            @Valid @RequestBody CompleteAdmissionOrderRequest request) {
        return ApiResponse.ok(orders.complete(admissionOrderId, request), "Đã ghi nhận thực hiện y lệnh");
    }

    /** inpatient-v1 #8. */
    @Operation(operationId = "cancelAdmissionOrder", tags = "Admission orders", summary = "Hủy y lệnh",
            description = "UC56 · BR-LT-15. Actor: A07. Quyền: VET của chi nhánh. Chuyển trạng thái: ORDERED → "
                + "CANCELLED (Y lệnh nội trú#3). Thứ tự lỗi: 400 hình thức → 403 vai trò → 404 → 403 chi nhánh → 403 "
                + "người phụ trách → 409 → 400 `BR-LT-15`.",
            parameters = @Parameter(name = "admissionOrderId", in = ParameterIn.PATH, example = "88"))
    @ApiErrorDoc(status = 400, description = "`BR-LT-15` lý do rỗng sau `strip()`; `VALIDATION_FAILED` thiếu hoặc "
            + "quá 300 ký tự; `MALFORMED_REQUEST` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Không phải VET · y lệnh của chi nhánh khác · không phải VET phụ trách Visit")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy y lệnh")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` trạng thái không cho phép (03 #24, #25)")
    @PostMapping("/admission-orders/{admissionOrderId}/cancel")
    @PreAuthorize(VET)
    public ApiResponse<AdmissionOrderResponse> cancel(@PathVariable Long admissionOrderId,
            @Valid @RequestBody AdmissionOrderReasonRequest request) {
        return ApiResponse.ok(orders.cancel(admissionOrderId, request), "Đã hủy y lệnh");
    }
}
