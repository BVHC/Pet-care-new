package com.petcare.module.workforce.controller;

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

import com.petcare.module.workforce.dto.CreateShiftRequestRequest;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.service.ShiftRequestService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Yêu cầu nghỉ / đổi ca của nhân viên (UC36, workforce-v1 #9–11; docs/adr/0138 bước 6). Không chứa logic và không biết
 * mã rule: BR-NS-04 / BR-NS-05 do {@link ShiftRequestService} kiểm. Chỉ nhân viên A06–A08 và chỉ dữ liệu của chính họ
 * (BRANCH_MANAGER dùng #12–14 ở bước sau, không dùng {@code /me/*}). Dùng chung hằng quyền {@code STAFF} của
 * {@link ShiftAssignmentController} để hai nơi không lệch nhau.
 */
@RestController
@RequestMapping("/api")
public class ShiftRequestController {

    private final ShiftRequestService shiftRequests;

    public ShiftRequestController(ShiftRequestService shiftRequests) {
        this.shiftRequests = shiftRequests;
    }

    /** workforce-v1 #9. */
    @Operation(operationId = "createShiftRequest", tags = "Shift requests", summary = "Gửi yêu cầu nghỉ / đổi ca",
            description = "UC36 · BR-NS-04, BR-NS-08. Actor: A06–A08. Quyền: Nhân viên A06–A08, chỉ dữ liệu của mình. "
                + "Chuyển trạng thái: — → PENDING (Yêu cầu#1).")
    @ApiErrorDoc(status = 400, description = "`BR-NS-04` phân ca không `SCHEDULED` · còn dưới 24 giờ **[CFG]** · đã có "
            + "yêu cầu chờ · `SWAP` thiếu ca đích, `LEAVE` có ca đích · ca đích không đang dùng hoặc trùng ca hiện tại "
            + "· ca đích chồng giờ phân ca *khác* của người đó trong ngày (không tính chính ca đang đổi); "
            + "`VALIDATION_FAILED` thiếu / quá dài `reason`")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy phân ca của mình · ca đích không có trong chi nhánh")
    @PostMapping("/me/shift-requests")
    @PreAuthorize(ShiftAssignmentController.STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftRequestResponse> create(@Valid @RequestBody CreateShiftRequestRequest request) {
        return ApiResponse.created(shiftRequests.createShiftRequest(request), "Đã gửi yêu cầu");
    }

    /** workforce-v1 #10. */
    @Operation(operationId = "listMyShiftRequests", tags = "Shift requests", summary = "Yêu cầu nghỉ / đổi ca của tôi",
            description = "UC36 · BR-NS-03. Actor: A06–A08. Quyền: Nhân viên A06–A08, chỉ dữ liệu của mình. Chuyển "
                + "trạng thái: —. Mới nhất trước (`createdAt` giảm dần, trùng thì `shiftRequestId` giảm dần).",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "PENDING"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/me/shift-requests")
    @PreAuthorize(ShiftAssignmentController.STAFF)
    public ApiResponse<PageResponse<ShiftRequestResponse>> listMine(
            @RequestParam(required = false) ShiftRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shiftRequests.listMyShiftRequests(status, page, size), "Yêu cầu nghỉ / đổi ca của tôi");
    }

    /** workforce-v1 #11. */
    @Operation(operationId = "withdrawShiftRequest", tags = "Shift requests", summary = "Rút yêu cầu",
            description = "UC36 · BR-NS-05. Actor: A06–A08. Quyền: Người gửi. Chuyển trạng thái: PENDING → WITHDRAWN "
                + "(Yêu cầu#2). Chỉ đổi yêu cầu → `WITHDRAWN` (Yêu cầu#2); phân ca giữ nguyên.",
            parameters = {@Parameter(name = "shiftRequestId", in = ParameterIn.PATH, example = "15")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Yêu cầu không có hoặc không phải của mình (kể cả yêu cầu của đồng "
            + "nghiệp cùng chi nhánh)")
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING`")
    @PostMapping("/me/shift-requests/{shiftRequestId}/withdraw")
    @PreAuthorize(ShiftAssignmentController.STAFF)
    public ApiResponse<ShiftRequestResponse> withdraw(@PathVariable Long shiftRequestId) {
        return ApiResponse.ok(shiftRequests.withdrawShiftRequest(shiftRequestId), "Đã rút yêu cầu");
    }
}
