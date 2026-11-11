package com.petcare.module.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.ai.dto.ItemProfile;
import com.petcare.module.ai.dto.ItemProfileRow;
import com.petcare.module.ai.dto.ItemType;
import com.petcare.module.ai.service.ItemProfileService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Hồ sơ gợi ý của dịch vụ thẩm mỹ và sản phẩm — ai-v1 #12, #13 (UC94, BR-AI-17, 18, 20; docs/adr/0133). Chỉ SUPER_MANAGER.
 * Không chứa logic: {@link ItemProfileService} kiểm BR-AI-18 và luật {@code min ≤ max}; request chỉ ràng buộc khoảng giá trị
 * của từng trường (khớp cột).
 */
@RestController
@RequestMapping("/api/ai/item-profiles")
public class ItemProfileController {

    private final ItemProfileService profiles;

    public ItemProfileController(ItemProfileService profiles) {
        this.profiles = profiles;
    }

    /** ai-v1 #12. */
    @Operation(operationId = "listItemProfiles", tags = "Item profiles",
            summary = "Danh sách dịch vụ / sản phẩm kèm hồ sơ gợi ý",
            description = "UC94 · BR-AI-18, 20. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. Chỉ dịch vụ thẩm "
                + "mỹ và sản phẩm `GOODS` không kê đơn đang kinh doanh; loại chuồng dùng `kennel_types` nên không có ở đây "
                + "(BR-AI-20).",
            parameters = {
                    @Parameter(name = "itemType", in = ParameterIn.QUERY, example = "SERVICE"),
                    @Parameter(name = "missingOnly", in = ParameterIn.QUERY, example = "true",
                            description = "true = chỉ mục chưa có hồ sơ"),
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "tắm", description = "Tìm theo tên"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(KnowledgeController.SUPER_MANAGER)
    public ApiResponse<PageResponse<ItemProfileRow>> listItemProfiles(
            @RequestParam(required = false) ItemType itemType,
            @RequestParam(required = false) Boolean missingOnly,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(profiles.list(itemType, missingOnly, q, page, size));
    }

    /** ai-v1 #13. */
    @Operation(operationId = "saveItemProfile", tags = "Item profiles", summary = "Lưu hồ sơ gợi ý",
            description = "UC94 · BR-AI-18, 20, 17. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: —. Ghi đè toàn "
                + "bộ hồ sơ; gửi mọi trường rỗng để xóa thuộc tính (mục trở về điểm trung tính, BR-AI-20).",
            parameters = {
                    @Parameter(name = "itemType", in = ParameterIn.PATH, example = "SERVICE"),
                    @Parameter(name = "itemId", in = ParameterIn.PATH, example = "101")})
    @ApiErrorDoc(status = 400, description = "`BR-AI-18` mục là dịch vụ khám / tiêm, thuốc, vaccine hoặc sản phẩm kê đơn · "
            + "`VALIDATION_FAILED` `min` lớn hơn `max`")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Không tìm thấy dịch vụ / sản phẩm")
    @PutMapping("/{itemType}/{itemId}")
    @PreAuthorize(KnowledgeController.SUPER_MANAGER)
    public ApiResponse<ItemProfileRow> saveItemProfile(@PathVariable ItemType itemType, @PathVariable Long itemId,
            @Valid @RequestBody ItemProfile request) {
        return ApiResponse.ok(profiles.save(itemType, itemId, request));
    }
}
