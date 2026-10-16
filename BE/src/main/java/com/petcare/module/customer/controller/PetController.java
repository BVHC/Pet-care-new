package com.petcare.module.customer.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.customer.dto.AddWeightRequest;
import com.petcare.module.customer.dto.CreatePetRequest;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.PetWriteResultResponse;
import com.petcare.module.customer.dto.UpdatePetRequest;
import com.petcare.module.customer.dto.WeightRecordResponse;
import com.petcare.module.customer.service.PetService;
import com.petcare.module.customer.service.PetWeightService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Thú cưng và cân nặng của customer-v1 (UC24, UC25; BR-KH-02…08; docs/adr/0041): {@code /api/me/pets} (khách) và
 * {@code /api/pets/**} (khách là chủ hiện tại, hoặc nhân viên mọi chi nhánh — hồ sơ dùng chung toàn chuỗi). Thú của người
 * khác với khách là 404 cùng message với id không tồn tại; hồ sơ khách còn cờ chờ liên kết → 400 BR-TK-19. Bean
 * Validation của body chạy trước {@code @PreAuthorize} (docs/adr/0028).
 */
@RestController
@RequestMapping("/api")
public class PetController {

    static final String OWNER_ONLY = "hasRole('CUSTOMER')";
    static final String OWNER_OR_CLINIC_STAFF = "hasAnyRole('CUSTOMER','RECEPTIONIST','VET','CARETAKER')";
    static final String OWNER_OR_FRONT_DESK = "hasAnyRole('CUSTOMER','RECEPTIONIST')";

    private final PetService petService;
    private final PetWeightService weightService;

    public PetController(PetService petService, PetWeightService weightService) {
        this.petService = petService;
        this.weightService = weightService;
    }

    /** customer-v1 #13. Mảng thẳng trong {@code data}, theo mã thú tăng dần. */
    @Operation(operationId = "listMyPets", tags = "Pets",
            summary = "Thú cưng của tôi",
            description = "UC24 · BR-KH-05, BR-TK-19. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @GetMapping("/me/pets")
    @PreAuthorize(OWNER_ONLY)
    public ApiResponse<List<PetResponse>> listMyPets() {
        return ApiResponse.ok(petService.listMyPets());
    }

    /** customer-v1 #14 (BR-KH-02, BR-TK-19). Nghi trùng tên: vẫn 201, {@code duplicateNameWarning = true}. */
    @Operation(operationId = "createMyPet", tags = "Pets",
            summary = "Thêm thú cưng",
            description = "UC24 · BR-KH-02, BR-TK-19. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng thái: —.")
    @ApiErrorDoc(status = 400, description = "Ngày sinh ở tương lai · thiếu trường bắt buộc (BR-KH-02) · `BR-TK-19` "
            + "hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @PostMapping("/me/pets")
    @PreAuthorize(OWNER_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PetWriteResultResponse> createMyPet(@Valid @RequestBody CreatePetRequest request) {
        return ApiResponse.created(petService.createMyPet(request), "Đã thêm thú cưng");
    }

    /** customer-v1 #15. */
    @Operation(operationId = "getPet", tags = "Pets",
            summary = "Chi tiết thú cưng",
            description = "UC24 · BR-KH-03, 06, 08. Actor: A02, A06, A07, A08. Quyền: Chủ hiện tại; nhân viên mọi "
                + "chi nhánh. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "petId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/pets/{petId}")
    @PreAuthorize(OWNER_OR_CLINIC_STAFF)
    public ApiResponse<PetResponse> getPet(@PathVariable Long petId) {
        return ApiResponse.ok(petService.getPet(petId));
    }

    /** customer-v1 #16 (BR-KH-02, 03, 05). */
    @Operation(operationId = "updatePet", tags = "Pets",
            summary = "Sửa thông tin thú cưng",
            description = "UC24 · BR-KH-02, 03, 05. Actor: A02, A06. Quyền: Khách là chủ hiện tại · A06. Chuyển "
                + "trạng thái: —.",
            parameters = {
                    @Parameter(name = "petId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-KH-03` đổi loài khi đã có bệnh án / mũi tiêm · `BR-KH-05` thú đã "
            + "mất (chỉ đọc) · `BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PatchMapping("/pets/{petId}")
    @PreAuthorize(OWNER_OR_FRONT_DESK)
    public ApiResponse<PetWriteResultResponse> updatePet(@PathVariable Long petId,
            @Valid @RequestBody UpdatePetRequest request) {
        return ApiResponse.ok(petService.updatePet(petId, request), "Đã cập nhật thú cưng");
    }

    /** customer-v1 #20. Mảng thẳng trong {@code data}, mới nhất trước. */
    @Operation(operationId = "listPetWeights", tags = "Weights",
            summary = "Lịch sử cân nặng",
            description = "UC24, UC25 · BR-KH-04, 07. Actor: A02, A06, A07, A08. Quyền: Chủ hiện tại; nhân viên. "
                + "Chuyển trạng thái: —. Mới nhất trước.",
            parameters = {
                    @Parameter(name = "petId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/pets/{petId}/weights")
    @PreAuthorize(OWNER_OR_CLINIC_STAFF)
    public ApiResponse<List<WeightRecordResponse>> listWeights(@PathVariable Long petId) {
        return ApiResponse.ok(weightService.listWeights(petId));
    }

    /** customer-v1 #21 (BR-KH-04, 05): nguồn luôn {@code OWNER}. */
    @Operation(operationId = "addOwnerWeight", tags = "Weights",
            summary = "Chủ tự khai cân nặng",
            description = "UC24 · BR-KH-04. Actor: A02. Quyền: Khách là chủ hiện tại. Chuyển trạng thái: —. "
                + "source = OWNER (đánh dấu chủ tự khai).",
            parameters = {
                    @Parameter(name = "petId", in = ParameterIn.PATH, example = "88")})
    @ApiErrorDoc(status = 400, description = "`BR-KH-04` cân nặng ≤ 0 · `BR-KH-05` thú đã mất · `BR-TK-19` hồ sơ "
            + "còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/pets/{petId}/weights")
    @PreAuthorize(OWNER_ONLY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WeightRecordResponse> addOwnerWeight(@PathVariable Long petId,
            @Valid @RequestBody AddWeightRequest request) {
        return ApiResponse.created(weightService.addOwnerWeight(petId, request), "Đã ghi cân nặng");
    }
}
