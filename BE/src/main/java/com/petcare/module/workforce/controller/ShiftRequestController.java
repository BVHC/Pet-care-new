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
import com.petcare.module.workforce.dto.RejectShiftRequestRequest;
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
 * Yêu cầu nghỉ / đổi ca (UC36 workforce-v1 #9–11 của nhân viên, docs/adr/0138 bước 6; UC37 #12 danh sách của
 * BRANCH_MANAGER, bước 8). Không chứa logic và không biết mã rule: BR-NS-04 / BR-NS-05 do {@link ShiftRequestService}
 * kiểm. {@code /me/*} chỉ cho nhân viên A06–A08 và chỉ dữ liệu của chính họ; {@code /shift-requests} chỉ cho
 * BRANCH_MANAGER (từ chối #14 ở bước 9, duyệt #13 chỉ {@code LEAVE} ở N3-S5a, docs/adr/0139). Dùng chung hằng quyền {@code STAFF} / {@code BRANCH_MANAGER} của
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

    /** workforce-v1 #12. */
    @Operation(operationId = "listShiftRequests", tags = "Shift requests",
            summary = "Yêu cầu nghỉ / đổi ca của chi nhánh",
            description = "UC37 · BR-NS-06. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. Chuyển trạng thái: —. "
                + "`status` vắng = `PENDING`. Cũ nhất trước (`createdAt` tăng dần, trùng thì `shiftRequestId` tăng "
                + "dần) để xử lý theo thứ tự gửi.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "PENDING"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/shift-requests")
    @PreAuthorize(ShiftAssignmentController.BRANCH_MANAGER)
    public ApiResponse<PageResponse<ShiftRequestResponse>> listBranch(
            @RequestParam(required = false) ShiftRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shiftRequests.listBranchShiftRequests(status, page, size),
                "Yêu cầu nghỉ / đổi ca của chi nhánh");
    }

    /** workforce-v1 #13. */
    @Operation(operationId = "approveShiftRequest", tags = "Shift requests", summary = "Duyệt yêu cầu",
            description = "UC37 · BR-NS-06, BR-NS-08, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. "
                + "Chuyển trạng thái: PENDING → APPROVED (Yêu cầu#3); Phân ca#3 hoặc #5. Cùng một transaction: yêu cầu → "
                + "`APPROVED` (Yêu cầu#3) và phân ca → `ON_LEAVE` (Phân ca#3, `LEAVE`) hoặc đổi sang ca đích (Phân ca#5, "
                + "`SWAP`); một bước lỗi thì rollback cả hai. Thông báo ghi outbox cùng transaction (BR-NS-08).",
            parameters = {@Parameter(name = "shiftRequestId", in = ParameterIn.PATH, example = "15")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-06` phân ca đã bắt đầu · `SWAP`: ca đích đã bắt đầu, ngừng dùng "
            + "hoặc chồng giờ")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED_SCOPE_MISMATCH` yêu cầu thuộc chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING` (kiểm trước guard "
            + "400, A11)")
    @PostMapping("/shift-requests/{shiftRequestId}/approve")
    @PreAuthorize(ShiftAssignmentController.BRANCH_MANAGER)
    public ApiResponse<ShiftRequestResponse> approve(@PathVariable Long shiftRequestId) {
        return ApiResponse.ok(shiftRequests.approveShiftRequest(shiftRequestId), "Đã duyệt yêu cầu");
    }

    /** workforce-v1 #14. */
    @Operation(operationId = "rejectShiftRequest", tags = "Shift requests", summary = "Từ chối yêu cầu",
            description = "UC37 · BR-NS-06, BR-NS-08, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. "
                + "Chuyển trạng thái: PENDING → REJECTED (Yêu cầu#4). Chỉ đổi yêu cầu → `REJECTED` (Yêu cầu#4); phân "
                + "ca giữ nguyên. Thông báo ghi outbox cùng transaction (BR-NS-08).",
            parameters = {@Parameter(name = "shiftRequestId", in = ParameterIn.PATH, example = "15")})
    @ApiErrorDoc(status = 400, description = "`VALIDATION_FAILED` thiếu lý do từ chối (`BR-NS-06`)")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED_SCOPE_MISMATCH` yêu cầu thuộc chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` yêu cầu không còn `PENDING` (kiểm trước guard "
            + "400, A11)")
    @PostMapping("/shift-requests/{shiftRequestId}/reject")
    @PreAuthorize(ShiftAssignmentController.BRANCH_MANAGER)
    public ApiResponse<ShiftRequestResponse> reject(@PathVariable Long shiftRequestId,
            @Valid @RequestBody RejectShiftRequestRequest request) {
        return ApiResponse.ok(shiftRequests.rejectShiftRequest(shiftRequestId, request), "Đã từ chối yêu cầu");
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
