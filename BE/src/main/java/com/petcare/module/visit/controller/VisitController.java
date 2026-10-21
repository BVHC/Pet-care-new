package com.petcare.module.visit.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.petcare.module.visit.dto.AssignRequest;
import com.petcare.module.visit.dto.AssignResult;
import com.petcare.module.visit.dto.CreateVisitRequest;
import com.petcare.module.visit.dto.MoveVisitRequest;
import com.petcare.module.visit.dto.QueueServiceGroup;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.service.VisitCheckInService;
import com.petcare.module.visit.service.VisitDispatchService;
import com.petcare.module.visit.service.VisitQueueService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Tiếp nhận và hàng đợi — visit-v1 #1 tiếp nhận (UC44, UC47, A06; docs/adr/0059), #2 hàng đợi chi nhánh, #4 chi tiết
 * lượt (UC45, UC46, A05–A08; docs/adr/0060), #5 gán, #6 sắp lại (UC45, A06), #7 xác định ca cấp cứu (UC47, A06, A07;
 * docs/adr/0061). Body / tham số sai là 400 trước {@code @PreAuthorize}; chi nhánh lấy từ người đăng nhập, mọi rule
 * kiểm ở service. #3 hàng đợi của tôi ở {@link MyQueueController}.
 */
@RestController
@RequestMapping("/api/visits")
public class VisitController {

    /** Hợp đồng chỉ cho A06 tiếp nhận (visit-v1 mục B; 01 UC44; 03 #1). */
    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";

    /** Hợp đồng #2, #4: A05–A08 (UC45, UC46, UC47; CARETAKER theo visit-v1 A13). */
    static final String QUEUE_ROLES = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    /** Hợp đồng #7: A06, A07 (UC47). */
    static final String EMERGENCY_ROLES = "hasAnyRole('RECEPTIONIST','VET')";

    private final VisitCheckInService checkIns;
    private final VisitQueueService queue;
    private final VisitDispatchService dispatch;

    public VisitController(VisitCheckInService checkIns, VisitQueueService queue, VisitDispatchService dispatch) {
        this.checkIns = checkIns;
        this.queue = queue;
        this.dispatch = dispatch;
    }

    /** visit-v1 #1 (Visit#1 + Lịch hẹn#3 + Order#1 trong một transaction). */
    @Operation(operationId = "checkInVisit", tags = "Queue",
            summary = "Tiếp nhận (check-in lịch hẹn / walk-in / cấp cứu)",
            description = "UC44, UC47 · BR-TN-01…04, BR-CN-05, BR-LH-01, BR-KH-05. Actor: A06. Quyền: Lễ tân; chi nhánh "
                + "của lễ tân. Chuyển trạng thái: — → WAITING (Visit#1) · Lịch hẹn#3 · Order#1 (OPEN + dòng dịch vụ tự "
                + "sinh) · có dịch vụ Khám → Care Task#6. Trễ quá `visit.late_priority_minutes` (15 phút) [CFG] xếp như "
                + "walk-in. A9, A10, A11, A12. Thứ tự lỗi: 404 → 400 → 409 (docs/adr/0059). Có dịch vụ Khám: Care Task#6 "
                + "chưa nối, nối khi visit cài FollowUpApi. Khách mang nhiều thú thì tạo nhiều Visit.")
    @ApiErrorDoc(status = 400, description = "`BR-TN-01` walk-in thiếu dịch vụ / thú không khớp lịch hẹn / thú đã mất · "
            + "`BR-TN-02` lịch hẹn của chi nhánh khác hoặc ngoài cửa sổ check-in · `BR-CN-01` chi nhánh chưa ACTIVE · "
            + "`BR-CN-05` ngoài giờ mà chi nhánh không có cờ hoặc chưa đánh dấu cấp cứu · `BR-LH-01` walk-in: dịch vụ đã "
            + "ngừng, đang tắt tại chi nhánh hoặc không thuộc MEDICAL / GROOMING; có lịch: dịch vụ đã ngừng kinh doanh · "
            + "body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lịch hẹn không còn BOOKED (đã check-in / NO_SHOW: tiếp nhận như walk-in, "
            + "BR-TN-03)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<VisitResponse> checkInVisit(@Valid @RequestBody CreateVisitRequest request) {
        return ApiResponse.created(checkIns.checkInVisit(request), "Đã tiếp nhận");
    }

    /** visit-v1 #2. Thứ tự BR-TN-04; bộ lọc theo A1 (docs/adr/0060). */
    @Operation(operationId = "listQueue", tags = "Queue",
            summary = "Hàng đợi của chi nhánh",
            description = "UC45 · BR-TN-04, 09. Actor: A05, A06, A07, A08. Quyền: Nhân viên chi nhánh. Chuyển trạng "
                + "thái: —. A1, A13.",
            parameters = {
                    @Parameter(name = "date", in = ParameterIn.QUERY, example = "2026-10-20",
                            description = "Ngày check-in (giờ Việt Nam). Không gửi: lượt chưa kết thúc của mọi ngày; "
                                + "với status COMPLETED / CANCELLED là hôm nay (A1)"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "WAITING"),
                    @Parameter(name = "serviceGroup", in = ParameterIn.QUERY, example = "MEDICAL"),
                    @Parameter(name = "assigneeId", in = ParameterIn.QUERY, example = "45"),
                    @Parameter(name = "needsReassign", in = ParameterIn.QUERY, example = "true")})
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(QUEUE_ROLES)
    public ApiResponse<List<VisitResponse>> listQueue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) VisitStatus status,
            @RequestParam(required = false) QueueServiceGroup serviceGroup,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean needsReassign) {
        return ApiResponse.ok(queue.listQueue(new VisitQueueFilter(date, status, serviceGroup, assigneeId,
                needsReassign)));
    }

    /** visit-v1 #4. Không có → 404; chi nhánh khác → 403 (00-method §3.3). */
    @Operation(operationId = "getVisit", tags = "Queue",
            summary = "Chi tiết lượt",
            description = "UC45, UC46 · —. Actor: A05–A08. Quyền: Nhân viên chi nhánh. Chuyển trạng thái: —. A13.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · nhân viên xem lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @GetMapping("/{visitId}")
    @PreAuthorize(QUEUE_ROLES)
    public ApiResponse<VisitResponse> getVisit(@PathVariable Long visitId) {
        return ApiResponse.ok(queue.getVisit(visitId));
    }

    /** visit-v1 #5 (Visit#2; docs/adr/0061). */
    @Operation(operationId = "assignVisit", tags = "Queue",
            summary = "Gán / gán lại nhân viên (lượt chưa gọi)",
            description = "UC45 · BR-TN-05, 06, 08. Actor: A06. Quyền: Lễ tân chi nhánh. Chuyển trạng thái: WAITING → "
                + "WAITING (Visit#2). Dòng dịch vụ tự sinh và các dòng người được gán cũ đã thêm chuyển quyền cho người "
                + "được gán (docs/adr/0061). Gán đúng người đang được gán: 200, không ghi gì.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-TN-05` nhân viên không ACTIVE, khác chi nhánh hoặc sai chức vụ · body "
            + "sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt đã được gọi")
    @PostMapping("/{visitId}/assign")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<AssignResult> assignVisit(@PathVariable Long visitId,
            @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(dispatch.assignVisit(visitId, request), "Đã gán nhân viên");
    }

    /** visit-v1 #6 (BR-TN-04, A3; docs/adr/0061). */
    @Operation(operationId = "moveVisitInQueue", tags = "Queue",
            summary = "Sắp lại thứ tự hàng đợi",
            description = "UC45 · BR-TN-04. Actor: A06. Quyền: Lễ tân chi nhánh. Chuyển trạng thái: —. A3. Trả hàng đợi "
                + "sau khi sắp.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-TN-04` lượt đích là chính lượt này, thuộc chi nhánh khác, hoặc sắp vượt "
            + "ranh giới ca cấp cứu · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không có lượt hoặc lượt đích")
    @ApiErrorDoc(status = 409, description = "Lượt hoặc lượt đích không còn WAITING")
    @PostMapping("/{visitId}/move")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<List<VisitResponse>> moveVisitInQueue(@PathVariable Long visitId,
            @Valid @RequestBody MoveVisitRequest request) {
        return ApiResponse.ok(dispatch.moveVisitInQueue(visitId, request), "Đã sắp lại hàng đợi");
    }

    /** visit-v1 #7 (UC47, A2; docs/adr/0061). */
    @Operation(operationId = "markVisitEmergency", tags = "Queue",
            summary = "Xác định ca cấp cứu",
            description = "UC47 · BR-TN-04. Actor: A06, A07. Quyền: Nhân viên chi nhánh. Chuyển trạng thái: —. Lượt lên "
                + "đầu hàng đợi (A2).",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt đã được gọi hoặc đã là cấp cứu")
    @PostMapping("/{visitId}/mark-emergency")
    @PreAuthorize(EMERGENCY_ROLES)
    public ApiResponse<VisitResponse> markVisitEmergency(@PathVariable Long visitId) {
        return ApiResponse.ok(dispatch.markVisitEmergency(visitId), "Đã xác định ca cấp cứu");
    }
}
