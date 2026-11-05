package com.petcare.module.visit.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.HealthRecordResponse;
import com.petcare.module.visit.service.HealthRecordService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/** Hồ sơ sức khỏe của thú — visit-v1 #21 (UC25, UC53; A02, A06, A07, A08); docs/adr/0108. Rule kiểm ở service. */
@RestController
@RequestMapping("/api/pets/{petId}")
public class PetHealthRecordController {

    /** Hợp đồng #21: khách (chủ hiện tại, kiểm ở service), lễ tân, VET, CARETAKER. */
    static final String VIEW_ROLES = "hasAnyRole('CUSTOMER','RECEPTIONIST','VET','CARETAKER')";

    private final HealthRecordService healthRecords;

    public PetHealthRecordController(HealthRecordService healthRecords) {
        this.healthRecords = healthRecords;
    }

    @Operation(operationId = "getPetHealthRecord", tags = "Health record",
            summary = "Hồ sơ sức khỏe thú cưng",
            description = "UC25, UC53 · BR-KH-07, BR-KB-01, 04, 06. Actor: A02, A06, A07, A08. Quyền: Khách là chủ hiện "
                + "tại; nhân viên. Chuyển trạng thái: —. Khách không nhận internalNote và bản bổ sung nội bộ. Chỉ mũi "
                + "tiêm tại hệ thống.",
            parameters = @Parameter(name = "petId", in = ParameterIn.PATH, example = "5001"))
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403, description = "Không phải chủ hiện tại")
    @ApiErrorDoc(status = 404)
    @GetMapping("/health-record")
    @PreAuthorize(VIEW_ROLES)
    public ApiResponse<HealthRecordResponse> getPetHealthRecord(@PathVariable Long petId) {
        return ApiResponse.ok(healthRecords.getHealthRecord(petId), "Hồ sơ sức khỏe thú cưng");
    }
}
