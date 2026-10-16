package com.petcare.module.inventory.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.inventory.dto.InventoryItemResponse;
import com.petcare.module.inventory.dto.SetMinQuantityRequest;
import com.petcare.module.inventory.dto.StockLotResponse;
import com.petcare.module.inventory.service.InventoryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/inventory} của inventory-v1 (UC75, #11–14; BR-KO-01, 05, 07; docs/adr/0042). Nhân viên chi nhánh xem, chỉ
 * BRANCH_MANAGER đặt tồn tối thiểu; luôn làm trên chi nhánh của người gọi.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    static final String BRANCH_STAFF_READ = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";
    static final String LOT_READ = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST')";
    static final String BRANCH_MANAGER_ONLY = "hasRole('BRANCH_MANAGER')";

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** inventory-v1 #11. */
    @Operation(operationId = "listInventory", tags = "Inventory",
            summary = "Xem tồn kho chi nhánh",
            description = "UC75 · BR-KO-01, 07. Actor: A05, A06, A07, A08. Quyền: Nhân viên chi nhánh (chỉ "
                + "BRANCH_MANAGER sửa). Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "vaccine", description = "Tên hoặc SKU"),
                    @Parameter(name = "productType", in = ParameterIn.QUERY, example = "VACCINE"),
                    @Parameter(name = "belowMin", in = ParameterIn.QUERY, example = "true"),
                    @Parameter(name = "expiringWithinDays", in = ParameterIn.QUERY, example = "30",
                            description = "Có lô sắp hết hạn trong N ngày"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(BRANCH_STAFF_READ)
    public ApiResponse<PageResponse<InventoryItemResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) Boolean belowMin,
            @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inventory.listInventory(q, productType, Boolean.TRUE.equals(belowMin),
                expiringWithinDays, page, size));
    }

    /** inventory-v1 #12. */
    @Operation(operationId = "getInventoryItem", tags = "Inventory",
            summary = "Tồn của một sản phẩm",
            description = "UC75, UC66, UC48 · BR-KO-01. Actor: A05, A06, A07, A08. Quyền: Nhân viên chi nhánh. "
                + "Chuyển trạng thái: —. Dùng ở POS / kê đơn / tiêm để xem tồn khả dụng.",
            parameters = {
                    @Parameter(name = "productId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{productId}")
    @PreAuthorize(BRANCH_STAFF_READ)
    public ApiResponse<InventoryItemResponse> get(@PathVariable Long productId) {
        return ApiResponse.ok(inventory.getItem(productId));
    }

    /** inventory-v1 #13. Mảng thẳng trong {@code data}, thứ tự FEFO. */
    @Operation(operationId = "listStockLots", tags = "Inventory",
            summary = "Tồn theo lô, hạn dùng",
            description = "UC75, UC76 · BR-KO-01, 05. Actor: A05, A06. Quyền: Nhân viên chi nhánh. Chuyển trạng "
                + "thái: —. Sắp theo hạn dùng tăng dần (thứ tự FEFO).",
            parameters = {
                    @Parameter(name = "productId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{productId}/lots")
    @PreAuthorize(LOT_READ)
    public ApiResponse<List<StockLotResponse>> lots(@PathVariable Long productId) {
        return ApiResponse.ok(inventory.listLots(productId));
    }

    /** inventory-v1 #14. */
    @Operation(operationId = "setMinQuantity", tags = "Inventory",
            summary = "Đặt tồn tối thiểu",
            description = "UC75 · BR-KO-07. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "productId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PutMapping("/{productId}/min-quantity")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<InventoryItemResponse> setMin(@PathVariable Long productId,
            @Valid @RequestBody SetMinQuantityRequest request) {
        return ApiResponse.ok(inventory.setMinQuantity(productId, request.minQuantity()), "Đã đặt tồn tối thiểu");
    }
}
