package com.petcare.module.inventory.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.inventory.dto.CancelReceiptRequest;
import com.petcare.module.inventory.dto.StockReceiptRequest;
import com.petcare.module.inventory.dto.StockReceiptResponse;
import com.petcare.module.inventory.dto.StockReceiptSummaryResponse;
import com.petcare.module.inventory.entity.StockReceiptStatus;
import com.petcare.module.inventory.service.StockReceiptService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/stock-receipts} của inventory-v1 (UC74, #5–10; BR-KO-02, 03, 04; docs/adr/0042). Chỉ BRANCH_MANAGER, làm
 * việc trên chi nhánh của người gọi (phiếu chi nhánh khác → 403). Bean Validation của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/stock-receipts")
public class StockReceiptController {

    static final String BRANCH_MANAGER_ONLY = "hasRole('BRANCH_MANAGER')";

    private final StockReceiptService receipts;

    public StockReceiptController(StockReceiptService receipts) {
        this.receipts = receipts;
    }

    /** inventory-v1 #5. */
    @Operation(operationId = "listStockReceipts", tags = "Stock receipts",
            summary = "Danh sách phiếu nhập",
            description = "UC74 · BR-KO-03. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "DRAFT"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31"),
                    @Parameter(name = "supplierId", in = ParameterIn.QUERY, example = "3"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<PageResponse<StockReceiptSummaryResponse>> list(
            @RequestParam(required = false) StockReceiptStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(receipts.listReceipts(status, from, to, supplierId, page, size));
    }

    /** inventory-v1 #6 (Phiếu nhập#1). */
    @Operation(operationId = "createStockReceipt", tags = "Stock receipts",
            summary = "Tạo phiếu nhập nháp",
            description = "UC74 · BR-KO-03. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: — → "
                + "DRAFT (Phiếu nhập#1). Chưa ảnh hưởng tồn.")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockReceiptResponse> create(@Valid @RequestBody StockReceiptRequest request) {
        return ApiResponse.created(receipts.createReceipt(request), "Đã tạo phiếu nhập nháp");
    }

    /** inventory-v1 #7. */
    @Operation(operationId = "getStockReceipt", tags = "Stock receipts",
            summary = "Chi tiết phiếu nhập",
            description = "UC74 · BR-KO-03. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "receiptId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{receiptId}")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<StockReceiptResponse> get(@PathVariable Long receiptId) {
        return ApiResponse.ok(receipts.getReceipt(receiptId));
    }

    /** inventory-v1 #8 (A2: thay toàn bộ phiếu nháp). */
    @Operation(operationId = "updateStockReceipt", tags = "Stock receipts",
            summary = "Sửa phiếu nháp",
            description = "UC74 · BR-KO-03. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —. A2.",
            parameters = {
                    @Parameter(name = "receiptId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Phiếu không còn DRAFT")
    @PutMapping("/{receiptId}")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<StockReceiptResponse> update(@PathVariable Long receiptId,
            @Valid @RequestBody StockReceiptRequest request) {
        return ApiResponse.ok(receipts.updateReceipt(receiptId, request), "Đã cập nhật phiếu nhập");
    }

    /** inventory-v1 #9 (Phiếu nhập#2). */
    @Operation(operationId = "confirmStockReceipt", tags = "Stock receipts",
            summary = "Xác nhận phiếu nhập",
            description = "UC74 · BR-KO-02, 03, BR-SP-05. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng "
                + "thái: DRAFT → CONFIRMED (Phiếu nhập#2). Cộng tồn theo lô; cùng số lô và hạn thì cộng vào lô cũ.",
            parameters = {
                    @Parameter(name = "receiptId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-KO-02` nhà cung cấp ngừng hợp tác · `BR-KO-03` dòng số lượng ≤ 0 / "
            + "thiếu giá nhập / thiếu số lô, hạn dùng hoặc hạn không sau ngày nhập")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Phiếu không còn DRAFT")
    @PostMapping("/{receiptId}/confirm")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<StockReceiptResponse> confirm(@PathVariable Long receiptId) {
        return ApiResponse.ok(receipts.confirmReceipt(receiptId), "Đã xác nhận phiếu nhập");
    }

    /** inventory-v1 #10 (Phiếu nhập#3, #4). Body tùy chọn. */
    @Operation(operationId = "cancelStockReceipt", tags = "Stock receipts",
            summary = "Hủy phiếu nhập",
            description = "UC74 · BR-KO-03, 04, 06, BR-QT-15. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển "
                + "trạng thái: DRAFT → CANCELLED (Phiếu nhập#3) · CONFIRMED → CANCELLED (Phiếu nhập#4). Phiếu "
                + "CONFIRMED: trừ lại tồn, ghi audit. Không xóa cứng phiếu.",
            parameters = {
                    @Parameter(name = "receiptId", in = ParameterIn.PATH, example = "21")})
    @ApiErrorDoc(status = 400, description = "`BR-KO-04` tồn hiện tại của lô không đủ để trừ lại — dùng phiếu điều "
            + "chỉnh (BR-KO-06)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Phiếu đã CANCELLED")
    @PostMapping("/{receiptId}/cancel")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<StockReceiptResponse> cancel(@PathVariable Long receiptId,
            @Valid @RequestBody(required = false) CancelReceiptRequest request) {
        return ApiResponse.ok(receipts.cancelReceipt(receiptId, request), "Đã hủy phiếu nhập");
    }
}
