package com.petcare.module.visit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.AddPrescriptionRequest;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.service.PrescriptionService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Kê đơn #16 và xóa dòng đơn #17 (UC48; A07 được gán lượt; docs/adr/0077, 0078). Body sai là 400 trước {@code @PreAuthorize}; mọi rule
 * kiểm ở service.
 */
@RestController
@RequestMapping("/api/visits/{visitId}")
public class PrescriptionController {

    /** Hợp đồng #16: A07 (người được gán lượt kiểm ở service). */
    static final String VET = "hasRole('VET')";

    private final PrescriptionService prescriptions;

    public PrescriptionController(PrescriptionService prescriptions) {
        this.prescriptions = prescriptions;
    }

    @Operation(operationId = "addPrescriptionItem", tags = "Prescriptions", summary = "Kê đơn một dòng thuốc",
            description = "UC48, UC67 · BR-KB-03, BR-SP-01, BR-BH-02, BR-QT-15. Actor: A07. Quyền: VET được gán lượt. "
                + "Chuyển trạng thái: IN_PROGRESS giữ nguyên · dòng Order DRUG (đủ tồn / giảm số lượng) · bệnh án rỗng "
                + "nếu chưa có. A5, A26, A27, A28, A29. Thứ tự lỗi: 400 body → 403 vai trò → 404 → 403 chi nhánh → 404 "
                + "GROOMING → 403 người phụ trách → 409 → 400 BR-KB-03 (thuốc) → 400 BR-KB-03 (tồn) (docs/adr/0077). Đủ "
                + "tồn → 1 dòng Order DRUG giá snapshot; giảm số lượng → dòng DRUG bằng tồn; mua ngoài → không sinh dòng, "
                + "giữ số lượng; không tách dòng. Kho trừ lúc thu tiền (BR-BH-04). Ghi audit.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-KB-03` thuốc không tồn tại, không phải thuốc kê đơn hoặc đã ngừng kinh "
            + "doanh · thiếu tồn khả dụng (message ghi số tồn hiện có) — gửi lại kèm shortageResolution · "
            + "`REDUCE_TO_AVAILABLE` khi tồn bằng 0 (A27) · body sai (`VALIDATION_FAILED`, `MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại")
    @ApiErrorDoc(status = 404, description = "Không có Visit hoặc Visit nhóm GROOMING")
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS")
    @PostMapping("/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(VET)
    public ApiResponse<PrescriptionItemResponse> addPrescriptionItem(@PathVariable Long visitId,
            @Valid @RequestBody AddPrescriptionRequest request) {
        return ApiResponse.created(prescriptions.add(visitId, request), "Đã kê đơn");
    }

    @Operation(operationId = "removePrescriptionItem", tags = "Prescriptions", summary = "Xóa dòng đơn thuốc",
            description = "UC48 · BR-KB-03, BR-BH-02, BR-QT-15. Actor: A07. Quyền: VET phụ trách hiện tại (A30). "
                + "Chuyển trạng thái: IN_PROGRESS giữ nguyên · xóa dòng Order DRUG đi kèm (trừ dòng mua ngoài) · bệnh án "
                + "giữ nguyên. A30. Thứ tự lỗi: 400 id → 403 vai trò → 404 lượt → 403 chi nhánh → 404 GROOMING → 403 "
                + "người phụ trách → 409 → 404 dòng đơn (docs/adr/0078). Xóa dòng đơn rồi xóa dòng Order DRUG tương ứng "
                + "trong cùng transaction; dòng mua ngoài chỉ xóa dòng đơn. Kho không đổi (thuốc chỉ trừ lúc thu tiền). "
                + "Ghi audit.",
            parameters = {@Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"),
                    @Parameter(name = "itemId", in = ParameterIn.PATH, example = "801")})
    @ApiErrorDoc(status = 400, description = "`MALFORMED_REQUEST` id sai kiểu; BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại (A30)")
    @ApiErrorDoc(status = 404, description = "Không có Visit, Visit nhóm GROOMING hoặc dòng đơn thuốc không thuộc lượt này")
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS — sau COMPLETED, sai sót ghi bằng bản bổ sung")
    @DeleteMapping("/prescriptions/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(VET)
    public void removePrescriptionItem(@PathVariable Long visitId, @PathVariable Long itemId) {
        prescriptions.remove(visitId, itemId);
    }
}
