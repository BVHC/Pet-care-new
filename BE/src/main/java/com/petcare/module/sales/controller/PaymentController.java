package com.petcare.module.sales.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.sales.dto.CreatePaymentRequest;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.dto.PaymentPreviewRequest;
import com.petcare.module.sales.dto.PaymentPreviewResponse;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.service.PaymentQueryService;
import com.petcare.module.sales.service.PaymentService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Thu tiền tại quầy của sales-v1 (#12–#15; UC70; BR-TG-01…04, BR-BH-04, 06; docs/adr/0064): lễ tân có ca mở thu một
 * hoặc nhiều Order PENDING của cùng khách; xem phiếu thu cho BRANCH_MANAGER và lễ tân cùng chi nhánh. Bean Validation
 * của body chạy trước {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";
    static final String CASH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST')";

    private final PaymentService payments;
    private final PaymentQueryService queries;

    public PaymentController(PaymentService payments, PaymentQueryService queries) {
        this.payments = payments;
        this.queries = queries;
    }

    /** sales-v1 #12. */
    @Operation(operationId = "listPayableOrders", tags = "Payments",
            summary = "Order PENDING của khách tại chi nhánh",
            description = "UC70 · BR-TG-01, 03. Actor: A06. Quyền: Lễ tân chi nhánh. Chuyển trạng thái: —. Dùng để "
                + "chọn Order thu gộp.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "5")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/customers/{customerId}/payable-orders")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<List<OrderSummaryResponse>> listPayable(@PathVariable Long customerId) {
        return ApiResponse.ok(queries.listPayableOrders(customerId));
    }

    /** sales-v1 #13. */
    @Operation(operationId = "previewPayment", tags = "Payments",
            summary = "Xem trước khi thu (tổng tiền, dòng thiếu tồn)",
            description = "UC70 · BR-TG-02, 03, BR-BH-04. Actor: A06. Quyền: Lễ tân chi nhánh. Chuyển trạng thái: —. "
                + "Không ghi gì (A2).")
    @ApiErrorDoc(status = 400, description = "`BR-TG-03` Order khác khách / khác chi nhánh · `BR-TG-01` có Order "
            + "không PENDING")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/payments/preview")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<PaymentPreviewResponse> preview(@Valid @RequestBody PaymentPreviewRequest request) {
        return ApiResponse.ok(queries.previewPayment(request));
    }

    /** sales-v1 #14 (Order#8). */
    @Operation(operationId = "collectPayment", tags = "Payments",
            summary = "Thu tiền",
            description = "UC70, ST06 · BR-TG-01…04, BR-BH-04, 06, BR-KO-05. Actor: A06. Quyền: Lễ tân có ca OPEN của "
                + "chính mình. Chuyển trạng thái: PENDING → PAID (Order#8) cho mọi Order trong phiếu. Trừ kho FEFO "
                + "cùng transaction; ghi vào ca; ghi audit.")
    @ApiErrorDoc(status = 400, description = "`BR-TG-01` chưa mở ca · `BR-TG-02` số tiền khác tổng · `BR-TG-03` "
            + "Order khác khách / chi nhánh · `BR-BH-04` thiếu tồn (gọi preview để xem dòng thiếu)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Có Order không còn PENDING · xung đột đồng thời")
    @PostMapping("/payments")
    @PreAuthorize(RECEPTIONIST)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentResponse> collect(@Valid @RequestBody CreatePaymentRequest request) {
        return ApiResponse.created(payments.collectPayment(request), "Đã thu tiền");
    }

    /** sales-v1 #15. */
    @Operation(operationId = "getPayment", tags = "Payments",
            summary = "Chi tiết phiếu thu",
            description = "UC70 · —. Actor: A05, A06. Quyền: Nhân viên chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "paymentId", in = ParameterIn.PATH, example = "55")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/payments/{paymentId}")
    @PreAuthorize(CASH_STAFF)
    public ApiResponse<PaymentResponse> get(@PathVariable Long paymentId) {
        return ApiResponse.ok(queries.getPayment(paymentId));
    }
}
