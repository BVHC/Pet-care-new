package com.petcare.module.visit.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.MedicalRecordResponse;
import com.petcare.module.visit.dto.UpdateMedicalRecordRequest;
import com.petcare.module.visit.service.MedicalRecordService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;

/**
 * Bệnh án của lượt — visit-v1 #12 xem (UC48, UC53; A07, A08), #13 ghi (UC48; A07 được gán lượt); docs/adr/0064, 0065.
 * Body / tham số sai là 400 trước {@code @PreAuthorize}; mọi rule kiểm ở service.
 */
@RestController
@RequestMapping("/api/visits/{visitId}/medical-record")
public class MedicalRecordController {

    /** Hợp đồng #12: A07, A08 (CARETAKER chỉ xem — BR-KB-01). */
    static final String VIEW_ROLES = "hasAnyRole('VET','CARETAKER')";

    /** Hợp đồng #13: A07 (người được gán lượt kiểm ở service dưới khóa lượt). */
    static final String WRITE_ROLES = "hasRole('VET')";

    private final MedicalRecordService medicalRecords;

    public MedicalRecordController(MedicalRecordService medicalRecords) {
        this.medicalRecords = medicalRecords;
    }

    @Operation(operationId = "getMedicalRecord", tags = "Medical record",
            summary = "Xem bệnh án của lượt",
            description = "UC48, UC53 · BR-KB-01. Actor: A07, A08. Quyền: VET, CARETAKER chi nhánh (CARETAKER chỉ xem). "
                + "Chuyển trạng thái: —. Thứ tự lỗi: 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING (docs/adr/0065). "
                + "Lượt MEDICAL chưa ghi bệnh án trả khung rỗng; locked = Visit COMPLETED.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác")
    @ApiErrorDoc(status = 404, description = "Không có Visit hoặc Visit nhóm GROOMING (không có bệnh án)")
    @GetMapping
    @PreAuthorize(VIEW_ROLES)
    public ApiResponse<MedicalRecordResponse> getMedicalRecord(@PathVariable Long visitId) {
        return ApiResponse.ok(medicalRecords.getMedicalRecord(visitId), "Bệnh án của lượt");
    }

    @Operation(operationId = "updateMedicalRecord", tags = "Medical record",
            summary = "Ghi bệnh án / hẹn tái khám",
            description = "UC48 · BR-KB-01, 06. Actor: A07. Quyền: VET được gán lượt. Chuyển trạng thái: —. Thứ tự lỗi: "
                + "400 body → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 403 người phụ trách → 409 → 400 BR-KB-06 "
                + "(docs/adr/0065). PUT thay toàn bộ 5 trường; lần ghi đầu tạo bệnh án; ngày tái khám chỉ kiểm khi đổi; "
                + "nội dung không đổi: 200, không ghi, không audit.",
            parameters = @Parameter(name = "visitId", in = ParameterIn.PATH, example = "9001"))
    @ApiErrorDoc(status = 400, description = "`BR-KB-06` ngày tái khám không sau ngày khám hoặc quá 180 ngày [CFG] · "
            + "body sai (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 403, description = "Sai vai trò · lượt của chi nhánh khác · không phải VET phụ trách hiện tại")
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Lượt không IN_PROGRESS — bệnh án đã khóa, dùng bản bổ sung")
    @PutMapping
    @PreAuthorize(WRITE_ROLES)
    public ApiResponse<MedicalRecordResponse> updateMedicalRecord(@PathVariable Long visitId,
            @Valid @RequestBody UpdateMedicalRecordRequest request) {
        return ApiResponse.ok(medicalRecords.updateMedicalRecord(visitId, request), "Đã lưu bệnh án");
    }
}
