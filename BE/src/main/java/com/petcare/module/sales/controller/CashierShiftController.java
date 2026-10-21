package com.petcare.module.sales.controller;

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

import com.petcare.module.sales.dto.CashierShiftResponse;
import com.petcare.module.sales.dto.CloseShiftRequest;
import com.petcare.module.sales.dto.CurrentShiftResponse;
import com.petcare.module.sales.dto.OpenShiftRequest;
import com.petcare.module.sales.dto.ReconcileShiftRequest;
import com.petcare.module.sales.entity.CashierShiftStatus;
import com.petcare.module.sales.service.CashierShiftCloseService;
import com.petcare.module.sales.service.CashierShiftQueryService;
import com.petcare.module.sales.service.CashierShiftService;
import com.petcare.module.sales.service.ShiftReconcileService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Ca thu ngân của sales-v1 (#16–#21; UC70–UC72; BR-TG-01, 05; docs/adr/0064): lễ tân mở / chốt ca của mình,
 * BRANCH_MANAGER xem danh sách và đối soát. Bean Validation của body chạy trước {@code @PreAuthorize}; quyền chi tiết
 * (chủ ca, chi nhánh) nằm ở service.
 */
@RestController
@RequestMapping("/api")
public class CashierShiftController {

    static final String RECEPTIONIST = "hasRole('RECEPTIONIST')";
    static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";
    static final String CASH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST')";

    private final CashierShiftService shifts;
    private final CashierShiftCloseService closing;
    private final ShiftReconcileService reconciling;
    private final CashierShiftQueryService queries;

    public CashierShiftController(CashierShiftService shifts, CashierShiftCloseService closing,
            ShiftReconcileService reconciling, CashierShiftQueryService queries) {
        this.shifts = shifts;
        this.closing = closing;
        this.reconciling = reconciling;
        this.queries = queries;
    }

    /** sales-v1 #16 (Ca thu ngân#1, #2). */
    @Operation(operationId = "openCashierShift", tags = "Cashier shifts",
            summary = "Mở ca thu ngân",
            description = "UC71 · BR-TG-01, 05, BR-CN-05. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: — → OPEN "
                + "(Ca thu ngân#1 ca thường / #2 ca ngoài giờ).")
    @ApiErrorDoc(status = 400, description = "`BR-TG-05` ca thường ngoài giờ mở cửa / ngày nghỉ; ca ngoài giờ "
            + "trong giờ mở cửa · `BR-CN-05` chi nhánh không có cờ cấp cứu ngoài giờ")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 409, description = "Đã có ca OPEN của mình")
    @PostMapping("/cashier-shifts")
    @PreAuthorize(RECEPTIONIST)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CashierShiftResponse> open(@Valid @RequestBody OpenShiftRequest request) {
        return ApiResponse.created(shifts.openCashierShift(request), "Đã mở ca thu ngân");
    }

    /** sales-v1 #17. */
    @Operation(operationId = "getMyCurrentShift", tags = "Cashier shifts",
            summary = "Ca đang mở của tôi",
            description = "UC70, UC71 · BR-TG-01. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 403)
    @GetMapping("/me/cashier-shift")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<CurrentShiftResponse> getMine() {
        return ApiResponse.ok(queries.getMyCurrentShift());
    }

    /** sales-v1 #18 (Ca thu ngân#3). */
    @Operation(operationId = "closeCashierShift", tags = "Cashier shifts",
            summary = "Chốt ca",
            description = "UC71 · BR-TG-05. Actor: A06. Quyền: Lễ tân chủ ca. Chuyển trạng thái: OPEN → CLOSED "
                + "(Ca thu ngân#3). Tổng hợp giao dịch theo phương thức.",
            parameters = {
                    @Parameter(name = "shiftId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Ca không OPEN")
    @PostMapping("/cashier-shifts/{shiftId}/close")
    @PreAuthorize(RECEPTIONIST)
    public ApiResponse<CashierShiftResponse> close(@PathVariable Long shiftId,
            @Valid @RequestBody CloseShiftRequest request) {
        return ApiResponse.ok(closing.closeCashierShift(shiftId, request), "Đã chốt ca thu ngân");
    }

    /** sales-v1 #19. */
    @Operation(operationId = "listCashierShifts", tags = "Cashier shifts",
            summary = "Danh sách ca",
            description = "UC72 · BR-TG-05. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "CLOSED"),
                    @Parameter(name = "businessDate", in = ParameterIn.QUERY, example = "2026-10-21"),
                    @Parameter(name = "cashierId", in = ParameterIn.QUERY, example = "7"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping("/cashier-shifts")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<PageResponse<CashierShiftResponse>> list(
            @RequestParam(required = false) CashierShiftStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queries.listCashierShifts(status, businessDate, cashierId, page, size));
    }

    /** sales-v1 #20. */
    @Operation(operationId = "getCashierShift", tags = "Cashier shifts",
            summary = "Chi tiết ca",
            description = "UC71, UC72 · BR-TG-05. Actor: A05, A06. Quyền: BRANCH_MANAGER chi nhánh; lễ tân chủ ca. "
                + "Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "shiftId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/cashier-shifts/{shiftId}")
    @PreAuthorize(CASH_STAFF)
    public ApiResponse<CashierShiftResponse> get(@PathVariable Long shiftId) {
        return ApiResponse.ok(queries.getCashierShift(shiftId));
    }

    /** sales-v1 #21 (Ca thu ngân#6). */
    @Operation(operationId = "reconcileCashierShift", tags = "Cashier shifts",
            summary = "Đối soát ca",
            description = "UC72 · BR-TG-04, 05, BR-QT-15. Actor: A05. Quyền: BRANCH_MANAGER chi nhánh. Chuyển trạng "
                + "thái: CLOSED → RECONCILED (Ca thu ngân#6). Ghi chênh lệch và ghi chú (cả sai sót sau khi thu, "
                + "BR-TG-04); ghi audit.",
            parameters = {
                    @Parameter(name = "shiftId", in = ParameterIn.PATH, example = "12")})
    @ApiErrorDoc(status = 400, description = "`BR-TG-05` ca tự chốt chưa nhập số tiền thực đếm")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Ca không CLOSED")
    @PostMapping("/cashier-shifts/{shiftId}/reconcile")
    @PreAuthorize(BRANCH_MANAGER)
    public ApiResponse<CashierShiftResponse> reconcile(@PathVariable Long shiftId,
            @Valid @RequestBody ReconcileShiftRequest request) {
        return ApiResponse.ok(reconciling.reconcileCashierShift(shiftId, request), "Đã đối soát ca thu ngân");
    }
}
