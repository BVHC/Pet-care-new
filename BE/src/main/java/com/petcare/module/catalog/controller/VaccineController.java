package com.petcare.module.catalog.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.catalog.dto.CreateProtocolRequest;
import com.petcare.module.catalog.dto.UpdateProtocolRequest;
import com.petcare.module.catalog.dto.UpdateVaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccinationProtocolResponse;
import com.petcare.module.catalog.dto.VaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccineTypeResponse;
import com.petcare.module.catalog.service.VaccinationProtocolService;
import com.petcare.module.catalog.service.VaccineTypeService;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** {@code /api/vaccine-types} và {@code /api/vaccination-protocols} của catalog-v1 (UC31). */
@RestController
@RequestMapping("/api")
public class VaccineController {

    private final VaccineTypeService vaccineTypes;
    private final VaccinationProtocolService protocols;

    public VaccineController(VaccineTypeService vaccineTypes, VaccinationProtocolService protocols) {
        this.vaccineTypes = vaccineTypes;
        this.protocols = protocols;
    }

    @GetMapping("/vaccine-types")
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<List<VaccineTypeResponse>> listVaccineTypes(@RequestParam(required = false) Species species,
            @RequestParam(required = false) Boolean isActive) {
        return ApiResponse.ok(vaccineTypes.listVaccineTypes(species, isActive));
    }

    @PostMapping("/vaccine-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<VaccineTypeResponse> createVaccineType(@Valid @RequestBody VaccineTypeRequest request) {
        return ApiResponse.created(vaccineTypes.createVaccineType(request), "Đã tạo loại vaccine");
    }

    @PatchMapping("/vaccine-types/{vaccineTypeId}")
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<VaccineTypeResponse> updateVaccineType(@PathVariable Long vaccineTypeId,
            @Valid @RequestBody UpdateVaccineTypeRequest request) {
        return ApiResponse.ok(vaccineTypes.updateVaccineType(vaccineTypeId, request), "Đã cập nhật loại vaccine");
    }

    @DeleteMapping("/vaccine-types/{vaccineTypeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public void deleteVaccineType(@PathVariable Long vaccineTypeId) {
        vaccineTypes.deleteVaccineType(vaccineTypeId);
    }

    @GetMapping("/vaccination-protocols")
    @PreAuthorize(CatalogAccess.STAFF_READ)
    public ApiResponse<List<VaccinationProtocolResponse>> listProtocols(
            @RequestParam(required = false) Species species,
            @RequestParam(required = false) Long vaccineTypeId,
            @RequestParam(required = false) Boolean isActive) {
        return ApiResponse.ok(protocols.listProtocols(species, vaccineTypeId, isActive));
    }

    @PostMapping("/vaccination-protocols")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<VaccinationProtocolResponse> createProtocol(@Valid @RequestBody CreateProtocolRequest request) {
        return ApiResponse.created(protocols.createProtocol(request), "Đã thêm dòng phác đồ");
    }

    @PatchMapping("/vaccination-protocols/{protocolId}")
    @PreAuthorize(CatalogAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<VaccinationProtocolResponse> updateProtocol(@PathVariable Long protocolId,
            @Valid @RequestBody UpdateProtocolRequest request) {
        return ApiResponse.ok(protocols.updateProtocol(protocolId, request), "Đã cập nhật dòng phác đồ");
    }
}
