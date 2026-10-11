package com.petcare.module.branch.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.branch.dto.CreateHolidayRequest;
import com.petcare.module.branch.dto.HolidayImpactRequest;
import com.petcare.module.branch.dto.HolidayResponse;
import com.petcare.module.branch.dto.OpeningHoursImpactRequest;
import com.petcare.module.branch.dto.OpeningHoursVersionResponse;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.dto.SetOpeningHoursRequest;
import com.petcare.module.branch.service.HolidayService;
import com.petcare.module.branch.service.OpeningHoursService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** Giờ mở cửa và ngày nghỉ của branch-v1 (UC14, endpoint #6–12). */
@RestController
@RequestMapping("/api/branches/{branchId}")
public class BranchScheduleController {

    private final OpeningHoursService openingHours;
    private final HolidayService holidays;

    public BranchScheduleController(OpeningHoursService openingHours, HolidayService holidays) {
        this.openingHours = openingHours;
        this.holidays = holidays;
    }

    @GetMapping("/opening-hours")
    @PreAuthorize(BranchAccess.STAFF_READ)
    public ApiResponse<List<OpeningHoursVersionResponse>> listOpeningHours(@PathVariable Long branchId) {
        return ApiResponse.ok(openingHours.listVersions(branchId));
    }

    @PostMapping("/opening-hours/impact")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<ScheduleImpact> previewOpeningHoursImpact(@PathVariable Long branchId,
            @Valid @RequestBody OpeningHoursImpactRequest request) {
        return ApiResponse.ok(openingHours.previewImpact(branchId, request));
    }

    @PutMapping("/opening-hours")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<OpeningHoursVersionResponse> setOpeningHours(@PathVariable Long branchId,
            @Valid @RequestBody SetOpeningHoursRequest request) {
        return ApiResponse.ok(openingHours.setOpeningHours(branchId, request), "Đã cập nhật giờ mở cửa");
    }

    @GetMapping("/holidays")
    @PreAuthorize(BranchAccess.STAFF_READ)
    public ApiResponse<List<HolidayResponse>> listHolidays(@PathVariable Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(holidays.listHolidays(branchId, from, to));
    }

    @PostMapping("/holidays/impact")
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<ScheduleImpact> previewHolidayImpact(@PathVariable Long branchId,
            @Valid @RequestBody HolidayImpactRequest request) {
        return ApiResponse.ok(holidays.previewImpact(branchId, request));
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public ApiResponse<HolidayResponse> addHoliday(@PathVariable Long branchId,
            @Valid @RequestBody CreateHolidayRequest request) {
        return ApiResponse.created(holidays.addHoliday(branchId, request), "Đã thêm ngày nghỉ");
    }

    @DeleteMapping("/holidays/{holidayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(BranchAccess.BRANCH_MANAGER_WRITE)
    public void deleteHoliday(@PathVariable Long branchId, @PathVariable Long holidayId) {
        holidays.deleteHoliday(branchId, holidayId);
    }
}
