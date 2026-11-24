package com.petcare.module.returns.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.returns.dto.CreateReturnRequest;
import com.petcare.module.returns.dto.ReturnRequestResponse;
import com.petcare.module.returns.service.ReturnRequestService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/return-requests")
public class ReturnRequestController {

    private final ReturnRequestService requests;

    public ReturnRequestController(ReturnRequestService requests) {
        this.requests = requests;
    }

    @Operation(operationId = "createReturnRequest", tags = "Return requests", summary = "Tạo yêu cầu trả hàng",
            description = "UC68 · BR-BH-13. Lễ tân chỉ tạo cho Order trong chi nhánh của mình.")
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping
    @PreAuthorize("hasRole('RECEPTIONIST')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReturnRequestResponse> create(@Valid @RequestBody CreateReturnRequest request) {
        return ApiResponse.created(requests.create(request), "Đã tạo yêu cầu trả hàng");
    }
}
