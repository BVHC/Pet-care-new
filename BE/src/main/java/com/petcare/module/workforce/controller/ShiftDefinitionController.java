package com.petcare.module.workforce.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.workforce.dto.CreateShiftDefinitionRequest;
import com.petcare.module.workforce.dto.ShiftDefinitionResponse;
import com.petcare.module.workforce.dto.UpdateShiftDefinitionRequest;
import com.petcare.module.workforce.service.ShiftDefinitionService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Ca làm việc của chi nhánh (UC34, workforce-v1 #1–3; docs/adr/0138). Không chứa logic và không biết mã rule:
 * BR-NS-01 do {@link ShiftDefinitionService} kiểm.
 */
@RestController
@RequestMapping("/api")
public class ShiftDefinitionController {

    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";
    static final String BRANCH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')";

    private final ShiftDefinitionService shiftDefinitions;

    public ShiftDefinitionController(ShiftDefinitionService shiftDefinitions) {
        this.shiftDefinitions = shiftDefinitions;
    }

    /** workforce-v1 #1. */
    @Operation(operationId = "listShiftDefinitions", tags = "Shift definitions",
            summary = "Danh sách ca làm việc của chi nhánh",
            description = "UC34, UC35, UC36 · BR-NS-01. Actor: A05, A06–A08. Quyền: BRANCH_MANAGER của chi nhánh; "
                + "A06–A08 của chi nhánh mình (chỉ đọc). Chuyển trạng thái: —. Không phân trang. BRANCH_MANAGER: "
                + "`activeOnly` vắng = false (cả ca ngừng dùng). A06–A08 chỉ nhận ca đang dùng, `activeOnly` bị bỏ "
                + "qua (A10); nhân viên cần danh sách này để điền `items` của `PUT` đăng ký tuần và chọn ca đích của "
                + "`SWAP`. Sắp theo `startTime` tăng dần.",
            parameters = {@Parameter(name = "activeOnly", in = ParameterIn.QUERY, example = "false")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/shift-definitions")
    @PreAuthorize(BRANCH_STAFF)
    public ApiResponse<List<ShiftDefinitionResponse>> list(@RequestParam(required = false) Boolean activeOnly) {
        return ApiResponse.ok(shiftDefinitions.listShiftDefinitions(activeOnly), "Danh sách ca làm việc");
    }

    /** workforce-v1 #2. */
    @Operation(operationId = "createShiftDefinition", tags = "Shift definitions",
            summary = "Tạo ca làm việc",
            description = "UC34 · BR-NS-01, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. "
                + "Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400, description = "`BR-NS-01` tên trùng trong chi nhánh · giờ bắt đầu không nhỏ hơn giờ "
            + "kết thúc; `VALIDATION_FAILED` sai kiểu / quá dài")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hai yêu cầu cùng tạo / đổi tên thành một tên "
            + "trùng nhau cùng lúc (ràng buộc tên duy nhất): tải lại và thử lại")
    @PostMapping("/shift-definitions")
    @PreAuthorize(BRANCH_MANAGER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftDefinitionResponse> create(@Valid @RequestBody CreateShiftDefinitionRequest request) {
        return ApiResponse.created(shiftDefinitions.createShiftDefinition(request), "Đã tạo ca làm việc");
    }

    /** workforce-v1 #3. */
    @Operation(operationId = "updateShiftDefinition", tags = "Shift definitions",
            summary = "Sửa / ngừng dùng ca làm việc",
            description = "UC34 · BR-NS-01, BR-NS-10. Actor: A05. Quyền: BRANCH_MANAGER của chi nhánh. "
                + "Chuyển trạng thái: —. Chỉ các trường có trong body được đổi; body không có trường nào là lỗi. "
                + "`active = true` bật lại ca đã ngừng dùng (A10).",
            parameters = {@Parameter(name = "shiftDefinitionId", in = ParameterIn.PATH, example = "7")})
    @ApiErrorDoc(status = 400, description = "`BR-NS-01` tên trùng · giờ bắt đầu không nhỏ hơn giờ kết thúc · sửa giờ "
            + "khi ca đã có phân ca · ngừng dùng khi ca còn phân ca `SCHEDULED` từ hôm nay; `VALIDATION_FAILED` "
            + "body không có trường nào · sai kiểu / quá dài")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED_SCOPE_MISMATCH` ca thuộc chi nhánh khác")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hai yêu cầu cùng tạo / đổi tên thành một tên "
            + "trùng nhau cùng lúc (ràng buộc tên duy nhất): tải lại và thử lại")
    @PatchMapping("/shift-definitions/{shiftDefinitionId}")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<ShiftDefinitionResponse> update(@PathVariable Long shiftDefinitionId,
            @Valid @RequestBody UpdateShiftDefinitionRequest request) {
        return ApiResponse.ok(shiftDefinitions.updateShiftDefinition(shiftDefinitionId, request),
                "Đã cập nhật ca làm việc");
    }
}
