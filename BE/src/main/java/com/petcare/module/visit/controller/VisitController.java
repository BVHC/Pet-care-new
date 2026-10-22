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
import com.petcare.module.visit.dto.CancelVisitRequest;
import com.petcare.module.visit.dto.CreateVisitRequest;
import com.petcare.module.visit.dto.MoveVisitRequest;
import com.petcare.module.visit.dto.ReassignRequest;
import com.petcare.module.visit.dto.QueueServiceGroup;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.service.VisitCheckInService;
import com.petcare.module.visit.service.VisitCompletionService;
import com.petcare.module.visit.service.VisitDispatchService;
import com.petcare.module.visit.service.VisitProgressService;
import com.petcare.module.visit.service.VisitQueueService;
import com.petcare.module.visit.service.VisitReassignService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Tiếp nhận và hàng đợi — visit-v1 #1 tiếp nhận (UC44, UC47, A06; docs/adr/0059), #2 hàng đợi chi nhánh, #4 chi tiết
 * lượt (UC45, UC46, A04–A08; docs/adr/0060, 0063), #5 gán, #6 sắp lại (UC45, A06), #7 xác định ca cấp cứu (UC47,
 * A06, A07; docs/adr/0061), #8 gọi lượt (UC46, A07, A08), #9 gán lại lượt đã gọi (UC45, A05 / A04; docs/adr/0063),
 * #11 hủy lượt khách bỏ về (UC45, A06; docs/adr/0062). Body / tham số
 * sai là 400 trước {@code @PreAuthorize}; chi nhánh lấy từ người đăng nhập, mọi rule kiểm ở service. #3 hàng đợi của
 * tôi ở {@link MyQueueController}.
 */
@RestController
@RequestMapping("/api/visits")
public class VisitController {

    /** Hợp đồng chỉ cho A06 tiếp nhận (visit-v1 mục B; 01 UC44; 03 #1). */
    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";

    /** Hợp đồng #2: A05–A08 (UC45, UC46, UC47; CARETAKER theo visit-v1 A13). */
    static final String QUEUE_ROLES = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    /** Hợp đồng #4: A04–A08 (visit-v1 A13, A14 — SUPER_MANAGER xem lượt cần gán lại, docs/adr/0063). */
    static final String DETAIL_ROLES =
            "hasAnyRole('SUPER_MANAGER','BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    /** Hợp đồng #7: A06, A07 (UC47). */
    static final String EMERGENCY_ROLES = "hasAnyRole('RECEPTIONIST','VET')";

    /** Hợp đồng #8: A07, A08 — người được gán lượt (UC46; 03 #3). */
    static final String CALL_ROLES = "hasAnyRole('VET','CARETAKER')";

    /** Hợp đồng #9: A05; A04 khi chi nhánh không còn BRANCH_MANAGER hoạt động — điều kiện kiểm ở service (03 #4). */
    static final String REASSIGN_ROLES = "hasAnyRole('BRANCH_MANAGER','SUPER_MANAGER')";

    private final VisitCheckInService checkIns;
    private final VisitQueueService queue;
    private final VisitDispatchService dispatch;
    private final VisitProgressService progress;
    private final VisitReassignService reassign;
    private final VisitCompletionService completion;

    public VisitController(VisitCheckInService checkIns, VisitQueueService queue, VisitDispatchService dispatch,
            VisitProgressService progress, VisitReassignService reassign, VisitCompletionService completion) {
        this.checkIns = checkIns;
        this.queue = queue;
        this.dispatch = dispatch;
        this.progress = progress;
        this.reassign = reassign;
        this.completion = completion;
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

    /** visit-v1 #4. Không có → 404; nhân viên chi nhánh khác → 403 (00-method §3.3); SUPER_MANAGER toàn chuỗi (A14). */
    @Operation(operationId = "getVisit", tags = "Queue",
            summary = "Chi tiết lượt",
            description = "UC45, UC46 · —. Actor: A04–A08. Quyền: Nhân viên chi nhánh; SUPER_MANAGER toàn chuỗi. Chuyển "
                + "trạng thái: —. A13, A14.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · nhân viên xem lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @GetMapping("/{visitId}")
    @PreAuthorize(DETAIL_ROLES)
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

    /** visit-v1 #8 (Visit#3; docs/adr/0062). Không có body. */
    @Operation(operationId = "callVisit", tags = "Queue",
            summary = "Gọi lượt",
            description = "UC46 · BR-TN-05, 09. Actor: A07, A08. Quyền: Người được gán lượt. Chuyển trạng thái: WAITING → "
                + "IN_PROGRESS (Visit#3). Người gọi được kiểm lại dưới khóa tài khoản của chính mình (docs/adr/0062).",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-TN-05` người gọi không còn hoạt động tại chi nhánh hoặc sai chức vụ với "
            + "nhóm dịch vụ (đổi chức vụ sau khi được gán) · tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · lượt không gán cho người gọi, kể "
            + "cả lượt chưa gán (BR-TN-05, 09)")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt không còn WAITING")
    @PostMapping("/{visitId}/call")
    @PreAuthorize(CALL_ROLES)
    public ApiResponse<VisitResponse> callVisit(@PathVariable Long visitId) {
        return ApiResponse.ok(progress.callVisit(visitId), "Đã gọi lượt");
    }

    /** visit-v1 #9 (Visit#4; docs/adr/0063). Thứ tự lỗi 404 → 403 → 409 → 400 (D4). */
    @Operation(operationId = "reassignCalledVisit", tags = "Queue",
            summary = "Gán lại lượt đã gọi",
            description = "UC45 · BR-TN-05, 08, BR-QT-15. Actor: A05 (A04 khi chi nhánh không còn BRANCH_MANAGER ACTIVE). "
                + "Quyền: BRANCH_MANAGER chi nhánh; SUPER_MANAGER theo điều kiện. Chuyển trạng thái: IN_PROGRESS → "
                + "IN_PROGRESS (Visit#4). A15. Thứ tự lỗi: 404 → 403 → 409 → 400 (docs/adr/0063). Gỡ cờ needsReassign. "
                + "Bệnh án giữ nguyên kèm tên người ghi; quyền xóa dòng Order người cũ đã thêm chuyển cho người mới; ghi "
                + "audit.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-TN-08` người phụ trách hiện tại vẫn đủ điều kiện (A15) · `BR-TN-05` "
            + "người mới không ACTIVE, khác chi nhánh hoặc sai chức vụ · thiếu lý do / body sai (`VALIDATION_FAILED`, "
            + "`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · BRANCH_MANAGER của chi nhánh khác · SUPER_MANAGER khi chi "
            + "nhánh còn BRANCH_MANAGER hoạt động")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS · lượt vừa được gán lại bởi thao tác khác "
            + "(`CONCURRENCY_CONFLICT`)")
    @PostMapping("/{visitId}/reassign")
    @PreAuthorize(REASSIGN_ROLES)
    public ApiResponse<VisitResponse> reassignCalledVisit(@PathVariable Long visitId,
            @Valid @RequestBody ReassignRequest request) {
        return ApiResponse.ok(reassign.reassignCalledVisit(visitId, request), "Đã gán lại lượt");
    }

    /** visit-v1 #10 (Visit#5 + Lịch hẹn#7 + Order#4 + khóa bệnh án trong một transaction; docs/adr/0069). */
    @Operation(operationId = "completeVisit", tags = "Completion",
            summary = "Hoàn tất lượt",
            description = "UC48, UC49, UC52 · BR-KB-01, 02, BR-SP-06, BR-LH-11. Actor: A07, A08. Quyền: Người được gán "
                + "lượt. Chuyển trạng thái: IN_PROGRESS → COMPLETED (Visit#5) · Lịch hẹn#7 · Order#4 (→ PENDING) · bệnh "
                + "án khóa. A16. Thứ tự lỗi: 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 400 BR-TN-05 → "
                + "409 → 400 BR-KB-02 (docs/adr/0069). Gỡ cờ needsReassign. Lỗi BR-KB-02 hoàn tác cả lịch hẹn, Order và "
                + "bệnh án.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-KB-02` có dịch vụ loại Khám mà chưa có chẩn đoán · chỉ có dịch vụ loại "
            + "Tiêm mà chưa ghi mũi nào · lượt Khám/Tiêm không còn dịch vụ Khám hoặc Tiêm nào (A16) · `BR-TN-05` chức vụ "
            + "hiện tại không phụ trách được nhóm dịch vụ của lượt (đổi chức vụ sau khi được gán) · tham số sai kiểu "
            + "(`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải người phụ trách hiện "
            + "tại, kể cả lượt chưa gán")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS")
    @PostMapping("/{visitId}/complete")
    @PreAuthorize(CALL_ROLES)
    public ApiResponse<VisitResponse> completeVisit(@PathVariable Long visitId) {
        return ApiResponse.ok(completion.completeVisit(visitId), "Đã hoàn tất lượt");
    }

    /** visit-v1 #11 (Visit#6 + Lịch hẹn#8 + Order#6 trong một transaction; docs/adr/0062). Body không bắt buộc (A4). */
    @Operation(operationId = "cancelVisit", tags = "Completion",
            summary = "Hủy lượt khách bỏ về",
            description = "UC45 · BR-TN-07. Actor: A06. Quyền: Lễ tân chi nhánh. Chuyển trạng thái: WAITING → CANCELLED "
                + "(Visit#6) · Lịch hẹn#8 · Order#6. Lịch hẹn → CANCELLED, không tính hủy muộn. Không gửi body: hủy không "
                + "lý do (A4).",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "Lý do quá 300 ký tự (`VALIDATION_FAILED`) · body / tham số sai "
            + "(`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt đã được gọi (BR-TN-07 — nhân viên hoàn tất theo BR-KB-02) hoặc đã kết "
            + "thúc")
    @PostMapping("/{visitId}/cancel")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<VisitResponse> cancelVisit(@PathVariable Long visitId,
            @Valid @RequestBody(required = false) CancelVisitRequest request) {
        return ApiResponse.ok(progress.cancelVisit(visitId, request), "Đã hủy lượt");
    }
}
