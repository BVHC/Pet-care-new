package com.petcare.module.inventory.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.inventory.dto.SupplierRequest;
import com.petcare.module.inventory.dto.SupplierResponse;
import com.petcare.module.inventory.dto.UpdateSupplierRequest;
import com.petcare.module.inventory.service.SupplierService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/suppliers} của inventory-v1 (UC73, #1–4; BR-KO-02; docs/adr/0042). Chỉ BRANCH_MANAGER; nhà cung cấp dùng
 * chung toàn chuỗi nên không có phạm vi chi nhánh. Bean Validation của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    static final String BRANCH_MANAGER_ONLY = "hasRole('BRANCH_MANAGER')";

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    /** inventory-v1 #1. Mảng thẳng trong {@code data}, sắp theo tên. */
    @Operation(operationId = "listSuppliers", tags = "Suppliers",
            summary = "Danh sách nhà cung cấp",
            description = "UC73, UC74 · BR-KO-02. Actor: A05. Quyền: BRANCH_MANAGER. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "isActive", in = ParameterIn.QUERY, example = "true"),
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "Minh Tâm")})
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<List<SupplierResponse>> list(@RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(suppliers.listSuppliers(isActive, q));
    }

    /** inventory-v1 #2. */
    @Operation(operationId = "createSupplier", tags = "Suppliers",
            summary = "Thêm nhà cung cấp",
            description = "UC73 · BR-KO-02. Actor: A05. Quyền: BRANCH_MANAGER (A1). Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @PostMapping
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        return ApiResponse.created(suppliers.createSupplier(request), "Đã thêm nhà cung cấp");
    }

    /** inventory-v1 #3. */
    @Operation(operationId = "updateSupplier", tags = "Suppliers",
            summary = "Sửa / ngừng hợp tác nhà cung cấp",
            description = "UC73 · BR-KO-02. Actor: A05. Quyền: BRANCH_MANAGER (A1). Chuyển trạng thái: —. Ngừng "
                + "hợp tác thì không chọn được ở phiếu nhập mới.",
            parameters = {
                    @Parameter(name = "supplierId", in = ParameterIn.PATH, example = "3")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/{supplierId}")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    public ApiResponse<SupplierResponse> update(@PathVariable Long supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        return ApiResponse.ok(suppliers.updateSupplier(supplierId, request), "Đã cập nhật nhà cung cấp");
    }

    /** inventory-v1 #4. 204 không body; xóa cứng. */
    @Operation(operationId = "deleteSupplier", tags = "Suppliers",
            summary = "Xóa nhà cung cấp chưa có phiếu nhập",
            description = "UC73 · BR-KO-02. Actor: A05. Quyền: BRANCH_MANAGER (A1). Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "supplierId", in = ParameterIn.PATH, example = "3")})
    @ApiErrorDoc(status = 400, description = "`BR-KO-02` đã có phiếu nhập — chỉ ngừng hợp tác")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @DeleteMapping("/{supplierId}")
    @PreAuthorize(BRANCH_MANAGER_ONLY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long supplierId) {
        suppliers.deleteSupplier(supplierId);
    }
}
