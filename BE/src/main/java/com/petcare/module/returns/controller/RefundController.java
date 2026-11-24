package com.petcare.module.returns.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.returns.dto.ConfirmRefundRequest;
import com.petcare.module.returns.dto.RefundResponse;
import com.petcare.module.returns.service.RefundConfirmService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundConfirmService refunds;

    @Operation(operationId = "confirmRefund", tags = "Refunds",
            summary = "Xác nhận đã hoàn tiền (tại quầy hoặc ngoài hệ thống)",
            description = "UC69 · BR-TG-13, BR-TG-14. Actor: A06, A05. Quyền: Lễ tân có ca `OPEN` của chính mình; "
                    + "hoặc BRANCH_MANAGER của chi nhánh với hoàn `GATEWAY` ở `FAILED`. Chuyển trạng thái: REQUESTED → "
                    + "SUCCEEDED (Hoàn tiền#2); FAILED → SUCCEEDED (Hoàn tiền#5). Hai đường. (a) A06, hoàn `CASH` / "
                    + "`TRANSFER` ở `REQUESTED`: ghi vào ca `OPEN` của chính người gọi (400 `BR-TG-01` nếu không có ca); "
                    + "`note` tùy chọn (mã giao dịch ngân hàng, lưu audit). (b) A05 của chi nhánh của Order, hoàn `GATEWAY` "
                    + "ở `FAILED`: xác nhận đã hoàn ngoài hệ thống, `note` bắt buộc (lưu audit, 400 nếu thiếu), không "
                    + "cần ca (Hoàn tiền#5). Yêu cầu trả hàng tương ứng (nếu có) → `REFUNDED` (Yêu cầu trả hàng#5).")
    @ApiErrorDoc(status = 400, description = "`BR-TG-01` đường (a) không có ca `OPEN` · `VALIDATION_FAILED` đường (b) thiếu `note`")
    @ApiErrorDoc(status = 403, description = "Đường (a) chỉ A06; đường (b) chỉ A05 của chi nhánh của Order; hoàn "
            + "`GATEWAY` ở `FAILED` mà người gọi là A06, hoặc hoàn tại quầy mà người gọi là A05")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` trạng thái hiện tại không cho phép (03 #16, #17) "
            + "· hoàn `GATEWAY` không ở `FAILED` (ST21 tự xử lý `REQUESTED`)")
    @PostMapping("/{refundId}/confirm")
    @PreAuthorize("hasAnyRole('RECEPTIONIST', 'BRANCH_MANAGER')")
    public ApiResponse<RefundResponse> confirm(@PathVariable @Positive long refundId,
            @Valid @RequestBody ConfirmRefundRequest request) {
        return ApiResponse.ok(refunds.confirm(refundId, request), "Đã xác nhận hoàn tiền");
    }
}
