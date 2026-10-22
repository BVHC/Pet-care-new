package com.petcare.module.inventory.controller;

import java.time.LocalDate;

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

import com.petcare.module.inventory.dto.StockAdjustmentRequest;
import com.petcare.module.inventory.dto.StockAdjustmentResponse;
import com.petcare.module.inventory.service.StockAdjustmentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/stock-adjustments} của inventory-v1 (UC76, #15–17; BR-KO-01, 06; BR-QT-15; docs/adr/0042). Chỉ
 * BRANCH_MANAGER, làm việc trên chi nhánh của người gọi. Bean Validation của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/stock-adjustments")
public class StockAdjustmentController {

    static final String BRANCH_MANAGER_ONLY = "hasRole('BRANCH_MANAGER')";

    private final StockAdjustmentService adjustments;

    public StockAdjustmentController(StockAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    /** inventory-v1 #15. Hiệu lực ngay; ghi movement {@code ADJUSTMENT} và audit. */
    @Operation(operationId = "createStockAdjustment", tags = "Adjustments",
            summary = "Lập phiếu điều chỉnh tồn",
            description = "UC76 · BR-KO-01, 06, BR-QT-15. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng "
                + "thái: —. Hiệu lực ngay, ghi movement ADJUSTMENT và audit. A4.")
    @ApiErrorDoc(status = 400, description = "`BR-KO-06` thiếu lý do / ghi chú khi OTHER · giảm quá tồn của lô · "
            + "`BR-KO-01` tồn âm")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockAdjustmentResponse> create(@Valid @RequestBody StockAdjustmentRequest request) {
        return ApiResponse.created(adjustments.createAdjustment(request), "Đã lập phiếu điều chỉnh tồn");
    }

    /** inventory-v1 #16. Khoảng ngày đóng theo ngày lập (giờ Việt Nam). */
    @Operation(operationId = "listStockAdjustments", tags = "Adjustments",
            summary = "Danh sách phiếu điều chỉnh",
            description = "UC76 · BR-KO-06. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<PageResponse<StockAdjustmentResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adjustments.listAdjustments(from, to, page, size));
    }

    /** inventory-v1 #17. */
    @Operation(operationId = "getStockAdjustment", tags = "Adjustments",
            summary = "Chi tiết phiếu điều chỉnh",
            description = "UC76 · BR-KO-06. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "adjustmentId", in = ParameterIn.PATH, example = "9")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{adjustmentId}")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<StockAdjustmentResponse> get(@PathVariable Long adjustmentId) {
        return ApiResponse.ok(adjustments.getAdjustment(adjustmentId));
    }
}
