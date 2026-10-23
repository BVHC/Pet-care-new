package com.petcare.module.catalog.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.PublicProductResponse;
import com.petcare.module.catalog.dto.PublicServiceResponse;
import com.petcare.module.catalog.service.PublicCatalogService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code /api/public/services}, {@code /product-categories}, {@code /products[/{productId}]} (catalog-v1 #19–22,
 * UC15, UC16). Path public: luôn xử lý như chưa đăng nhập, token gửi kèm bị bỏ qua (docs/adr/0005). Ràng buộc trên
 * tham số do Spring MVC tự validate, không đặt {@code @Validated} ở lớp; {@code page} / {@code size} ngoài khoảng
 * được kẹp lại như danh sách nhân viên.
 */
@RestController
@RequestMapping("/api/public")
public class PublicCatalogController {

    private final PublicCatalogService publicCatalog;

    public PublicCatalogController(PublicCatalogService publicCatalog) {
        this.publicCatalog = publicCatalog;
    }

    @Operation(operationId = "listPublicServices", tags = "Public", summary = "Dịch vụ công khai",
            description = "UC15 · BR-CK-01, 02, BR-SP-04. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @GetMapping("/services")
    public ApiResponse<List<PublicServiceResponse>> listPublicServices(
            @RequestParam(required = false) ServiceGroup group,
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId) {
        return ApiResponse.ok(publicCatalog.listServices(group, branchId));
    }

    @Operation(operationId = "listPublicProductCategories", tags = "Public",
            summary = "Danh mục sản phẩm công khai",
            description = "UC16 · BR-CK-01. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @GetMapping("/product-categories")
    public ApiResponse<List<ProductCategoryResponse>> listPublicProductCategories() {
        return ApiResponse.ok(publicCatalog.listCategories());
    }

    @Operation(operationId = "listPublicProducts", tags = "Public", summary = "Xem & tìm sản phẩm",
            description = "UC16 · BR-CK-01, 02, 03, BR-SP-01. Actor: A01, A02. Quyền: Public. "
                + "Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400)
    @GetMapping("/products")
    public ApiResponse<PageResponse<PublicProductResponse>> listPublicProducts(
            @RequestParam(required = false) @Size(max = 100, message = "Từ khóa tối đa 100 ký tự") String q,
            @RequestParam(required = false) @Positive(message = "categoryId phải là số dương") Long categoryId,
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(publicCatalog.listProducts(q, categoryId, branchId, page, size));
    }

    @Operation(operationId = "getPublicProduct", tags = "Public", summary = "Chi tiết sản phẩm công khai",
            description = "UC16 · BR-CK-01, 03, BR-SP-01. Actor: A01, A02. Quyền: Public. Chuyển trạng thái: —. "
                + "Thuốc kê đơn hoặc ngừng kinh doanh trả 404.")
    @ApiErrorDoc(status = 404)
    @GetMapping("/products/{productId}")
    public ApiResponse<PublicProductResponse> getPublicProduct(
            @PathVariable @Positive(message = "productId phải là số dương") Long productId) {
        return ApiResponse.ok(publicCatalog.getProduct(productId));
    }
}
