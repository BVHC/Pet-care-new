package com.petcare.module.customer.controller;

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

import com.petcare.module.customer.dto.CreateCounterCustomerRequest;
import com.petcare.module.customer.dto.CreatePetRequest;
import com.petcare.module.customer.dto.CustomerDetailResponse;
import com.petcare.module.customer.dto.CustomerSearchFilter;
import com.petcare.module.customer.dto.CustomerSearchItemResponse;
import com.petcare.module.customer.dto.CustomerWriteResultResponse;
import com.petcare.module.customer.dto.PetWriteResultResponse;
import com.petcare.module.customer.dto.UpdateCustomerRequest;
import com.petcare.module.customer.service.CounterCustomerService;
import com.petcare.module.customer.service.CustomerSearchService;
import com.petcare.module.customer.service.PetService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * {@code /api/customers} của customer-v1 — hồ sơ khách tại quầy và tra cứu (UC22, UC23; BR-KH-01, 10; docs/adr/0041).
 * Hồ sơ khách dùng chung toàn chuỗi nên nhân viên mọi chi nhánh đều thấy (không {@code BranchScope.check}); chỉ nhân viên
 * (CUSTOMER → 403). Bean Validation của body chạy trước {@code @PreAuthorize} (docs/adr/0028).
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    static final String FRONT_DESK_ONLY = "hasRole('RECEPTIONIST')";
    static final String CLINIC_STAFF = "hasAnyRole('RECEPTIONIST','VET','CARETAKER')";

    private final CustomerSearchService searchService;
    private final CounterCustomerService counterCustomers;
    private final PetService petService;

    public CustomerController(CustomerSearchService searchService, CounterCustomerService counterCustomers,
            PetService petService) {
        this.searchService = searchService;
        this.counterCustomers = counterCustomers;
        this.petService = petService;
    }

    /** customer-v1 #8. Các điều kiện đã gửi nối bằng AND; email luôn che trong kết quả. */
    @Operation(operationId = "searchCustomers", tags = "Customers",
            summary = "Tra cứu khách & thú cưng",
            description = "UC23 · BR-KH-01, 10. Actor: A06, A07, A08. Quyền: Nhân viên mọi chi nhánh. Chuyển "
                + "trạng thái: —. Cần ít nhất một điều kiện tìm.",
            parameters = {
                    @Parameter(name = "phone", in = ParameterIn.QUERY, example = "0901234567",
                            description = "Khớp đúng SĐT đã chuẩn hóa"),
                    @Parameter(name = "name", in = ParameterIn.QUERY, example = "Lan",
                            description = "Họ tên chứa chuỗi"),
                    @Parameter(name = "email", in = ParameterIn.QUERY, example = "lan.nguyen@gmail.com"),
                    @Parameter(name = "petName", in = ParameterIn.QUERY, example = "Mực"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "Không có điều kiện tìm nào")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(CLINIC_STAFF)
    public ApiResponse<PageResponse<CustomerSearchItemResponse>> search(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String petName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(searchService.search(new CustomerSearchFilter(phone, name, email, petName), page, size));
    }

    /** customer-v1 #9 (BR-KH-01, 10). SĐT trùng hồ sơ khác: vẫn 201, kèm danh sách nghi trùng. */
    @Operation(operationId = "createCounterCustomer", tags = "Customers",
            summary = "Tạo hồ sơ khách tại quầy",
            description = "UC22, UC44 · BR-KH-01, 10. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —. SĐT "
                + "trùng hồ sơ khác: vẫn tạo, trả danh sách nghi trùng (BR-KH-10).")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @PostMapping
    @PreAuthorize(FRONT_DESK_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CustomerWriteResultResponse> create(@Valid @RequestBody CreateCounterCustomerRequest request) {
        return ApiResponse.created(counterCustomers.createCounterCustomer(request), "Đã tạo hồ sơ khách");
    }

    /** customer-v1 #11 (BR-KH-01, 10, BR-TK-16). */
    @Operation(operationId = "updateCustomer", tags = "Customers",
            summary = "Sửa hồ sơ khách",
            description = "UC22 · BR-KH-01, 10, BR-TK-16. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 400, description = "Sửa email khi hồ sơ đã gắn tài khoản (dùng identity, A5) · thiếu SĐT "
            + "với hồ sơ tại quầy (BR-KH-01)")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/{customerId}")
    @PreAuthorize(FRONT_DESK_ONLY)
    public ApiResponse<CustomerWriteResultResponse> update(@PathVariable Long customerId,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return ApiResponse.ok(counterCustomers.updateCustomer(customerId, request), "Đã cập nhật hồ sơ khách");
    }

    /** customer-v1 #10. Hồ sơ và mọi thú (kể cả đã mất), theo mã tăng dần. */
    @Operation(operationId = "getCustomer", tags = "Customers",
            summary = "Chi tiết hồ sơ khách kèm thú cưng",
            description = "UC22, UC23 · BR-KH-01. Actor: A06, A07, A08. Quyền: Nhân viên. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{customerId}")
    @PreAuthorize(CLINIC_STAFF)
    public ApiResponse<CustomerDetailResponse> get(@PathVariable Long customerId) {
        return ApiResponse.ok(petService.getCustomerDetail(customerId));
    }

    /** customer-v1 #12 (BR-KH-02). Nghi trùng tên: vẫn 201, {@code duplicateNameWarning = true}. */
    @Operation(operationId = "createPetForCustomer", tags = "Pets",
            summary = "Lễ tân thêm thú cưng cho khách",
            description = "UC24 · BR-KH-02. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/{customerId}/pets")
    @PreAuthorize(FRONT_DESK_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PetWriteResultResponse> createPet(@PathVariable Long customerId,
            @Valid @RequestBody CreatePetRequest request) {
        return ApiResponse.created(petService.createForCustomer(customerId, request), "Đã thêm thú cưng");
    }
}
