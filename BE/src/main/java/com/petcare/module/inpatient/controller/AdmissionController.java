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

import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmissionSummaryResponse;
import com.petcare.module.inpatient.dto.AdmitRequest;
import com.petcare.module.inpatient.dto.DischargeRequest;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.service.AdmissionAdmitService;
import com.petcare.module.inpatient.service.AdmissionDischargeService;
import com.petcare.module.inpatient.service.AdmissionReadService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Nhập viện và đọc ca nội trú (inpatient-v1 #1–#3; UC55; docs/adr/0149 mục 16–21). Không chứa logic và không biết mã
 * rule. {@code @PreAuthorize} là hàng rào vai trò <b>duy nhất</b>: {@code BranchScope} cho ADMIN / SUPER_MANAGER đi qua ở
 * phạm vi toàn chuỗi, nên thiếu annotation thì họ đọc / nhập viện được (docs/adr/0149 mục 20).
 */
@RestController
@RequestMapping("/api")
public class AdmissionController {

    /** #1: chỉ VET (BR-LT-13, A07). */
    static final String VET = "hasRole('VET')";
    /** #2, #3: A06 chỉ xem, A07, A08 (inpatient-v1 §B); BRANCH_MANAGER chưa được xem (ADR-0148). */
    static final String STAFF = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";

    private final AdmissionAdmitService admit;
    private final AdmissionReadService reads;
    private final AdmissionDischargeService discharge;

    public AdmissionController(AdmissionAdmitService admit, AdmissionReadService reads,
            AdmissionDischargeService discharge) {
        this.admit = admit;
        this.reads = reads;
        this.discharge = discharge;
    }

    /** inpatient-v1 #1. */
    @Operation(operationId = "admitPet", tags = "Admissions", summary = "Nhập viện",
            description = "UC55 · BR-LT-13. Actor: A07. Quyền: VET của chi nhánh. Chuyển trạng thái: — → ADMITTED (Nhập "
                + "viện#1). Từ lưu trú: tạo Visit điều trị gán VET gọi (Order không có dòng tự sinh), đặt chỗ giữ nguyên "
                + "trạng thái (`CHECKED_IN` hoặc `OVERDUE`), thú ở nguyên chuồng (BR-LT-13, BR-LT-17). Visit điều trị là "
                + "Visit#7, Order#14; chuồng điều trị là Chuồng#6, #7 (03). Thứ tự lỗi: 400 hình thức → 403 vai trò → 400 "
                + "`BR-LT-13` nguồn → 404 Visit / đặt chỗ → 403 chi nhánh → 409 → 400 `BR-LT-13` điều kiện → 404 / 400 "
                + "dịch vụ → 404 / 400 chuồng (ADR-0149).")
    @ApiErrorDoc(status = 400, description = "`BR-LT-13` không đúng một trong `visitId` / `boardingBookingId` · thiếu "
            + "`kennelId` khi nhập từ khám / có `kennelId` khi nhập từ lưu trú · Visit không `IN_PROGRESS` / không của "
            + "mình / không thuộc nhóm Khám/Tiêm · đặt chỗ không `CHECKED_IN` / `OVERDUE` · thú đang lưu trú mà nhập từ "
            + "khám · dịch vụ không `INPATIENT` / ngừng kinh doanh / chưa bật ở chi nhánh (A5) · chuồng không "
            + "`AVAILABLE` / khác chi nhánh · thú đã mất; `VALIDATION_FAILED` thiếu `serviceId` hoặc id ≤ 0; "
            + "`MALFORMED_REQUEST` sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Không phải VET · Visit / đặt chỗ của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy Visit / đặt chỗ / dịch vụ / chuồng")
    @ApiErrorDoc(status = 409, description = "Thú đã có ca nội trú `ADMITTED` (`INVALID_STATE_TRANSITION`; hai lần "
            + "nhập viện đồng thời: bên thua `CONCURRENCY_CONFLICT`)")
    @PostMapping("/admissions")
    @PreAuthorize(VET)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdmissionResponse> admit(@Valid @RequestBody AdmitRequest request) {
        return ApiResponse.created(admit.admit(request), "Đã nhập viện");
    }

    /** inpatient-v1 #2. */
    @Operation(operationId = "listAdmissions", tags = "Admissions", summary = "Ca nội trú của chi nhánh",
            description = "UC55 · BR-LT-17. Actor: A06, A07, A08. Quyền: A06 chỉ xem; A07; A08 theo BR-LT-15. Chuyển "
                + "trạng thái: —. `status` vắng = `ADMITTED`. Chỉ ca của chi nhánh người gọi. Nhập viện sớm nhất trước "
                + "(trùng thì `admissionId` tăng dần). `kennelCode`: chuồng điều trị (nhập từ khám) hoặc chuồng của đặt "
                + "chỗ (nhập từ lưu trú).",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "ADMITTED"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "Sai vai trò")
    @GetMapping("/admissions")
    @PreAuthorize(STAFF)
    public ApiResponse<PageResponse<AdmissionSummaryResponse>> list(
            @RequestParam(required = false) AdmissionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reads.listAdmissions(status, page, size), "Ca nội trú của chi nhánh");
    }

    /** inpatient-v1 #3. */
    @Operation(operationId = "getAdmission", tags = "Admissions", summary = "Chi tiết ca nội trú",
            description = "UC55 · BR-LT-17. Actor: A06, A07, A08. Quyền: A06 chỉ xem; A07; A08 theo BR-LT-15. Chuyển "
                + "trạng thái: —.",
            parameters = @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"))
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "Sai vai trò · ca của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @GetMapping("/admissions/{admissionId}")
    @PreAuthorize(STAFF)
    public ApiResponse<AdmissionResponse> get(@PathVariable Long admissionId) {
        return ApiResponse.ok(reads.getAdmission(admissionId), "Chi tiết ca nội trú");
    }

    /** inpatient-v1 #4. */
    @Operation(operationId = "dischargeAdmission", tags = "Admissions", summary = "Xuất viện",
            description = "UC55 · BR-LT-14. Actor: A07. Quyền: VET của chi nhánh. Chuyển trạng thái: ADMITTED → "
                + "DISCHARGED (Nhập viện#2). Thêm **một** dòng dịch vụ nội trú (số lượng = ngày xuất − ngày nhập + 1 theo "
                + "ngày lịch giờ Việt Nam, đơn giá = `unitPrice` đã chốt, không phải giá danh mục hiện hành) vào Order của "
                + "Visit trong cùng transaction; giải phóng chuồng nếu được cấp riêng. `DECEASED` không tự đánh dấu thú "
                + "mất (BR-LT-14). Dòng này sau đó không sửa / xóa tay được (BR-LT-18). Thứ tự lỗi: 400 hình thức → 403 "
                + "vai trò → 400 `BR-LT-14` tóm tắt rỗng → 404 → 403 chi nhánh → 403 người phụ trách → 409 → 400 "
                + "`BR-LT-14` còn y lệnh.",
            parameters = @Parameter(name = "admissionId", in = ParameterIn.PATH, example = "51"))
    @ApiErrorDoc(status = 400, description = "`BR-LT-14` còn y lệnh `ORDERED` · thiếu tóm tắt (rỗng sau `strip()`); "
            + "`VALIDATION_FAILED` thiếu kết cục / tóm tắt hoặc tóm tắt quá 2000 ký tự; `MALFORMED_REQUEST` sai kiểu; "
            + "BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Không phải VET · ca của chi nhánh khác · không phải VET phụ trách Visit")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy ca nội trú")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` ca không còn `ADMITTED` (03 #24); hai xuất "
            + "viện đồng thời: bên sau 409")
    @PostMapping("/admissions/{admissionId}/discharge")
    @PreAuthorize(VET)
    public ApiResponse<AdmissionResponse> discharge(@PathVariable Long admissionId,
            @Valid @RequestBody DischargeRequest request) {
        return ApiResponse.ok(discharge.discharge(admissionId, request), "Đã xuất viện");
    }
}
