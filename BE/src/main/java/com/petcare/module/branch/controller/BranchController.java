package com.petcare.module.branch.controller;

import java.util.List;

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

import com.petcare.module.branch.dto.BranchResponse;
import com.petcare.module.branch.dto.CreateBranchRequest;
import com.petcare.module.branch.dto.UpdateBranchRequest;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.service.BranchService;
import com.petcare.platform.model.ApiResponse;

import jakarta.validation.Valid;

/** {@code /api/branches} của branch-v1 (UC12, endpoint #1–5). */
@RestController
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchService branches;

    public BranchController(BranchService branches) {
        this.branches = branches;
    }

    @GetMapping
    @PreAuthorize(BranchAccess.STAFF_READ)
    public ApiResponse<List<BranchResponse>> list(@RequestParam(required = false) BranchStatus status) {
        return ApiResponse.ok(branches.listBranches(status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(BranchAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<BranchResponse> create(@Valid @RequestBody CreateBranchRequest request) {
        return ApiResponse.created(branches.createBranch(request), "Đã tạo chi nhánh");
    }

    @GetMapping("/{branchId}")
    @PreAuthorize(BranchAccess.STAFF_READ)
    public ApiResponse<BranchResponse> get(@PathVariable Long branchId) {
        return ApiResponse.ok(branches.getBranch(branchId));
    }

    @PatchMapping("/{branchId}")
    @PreAuthorize(BranchAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<BranchResponse> update(@PathVariable Long branchId,
            @Valid @RequestBody UpdateBranchRequest request) {
        return ApiResponse.ok(branches.updateBranch(branchId, request), "Đã cập nhật chi nhánh");
    }

    @PostMapping("/{branchId}/activate")
    @PreAuthorize(BranchAccess.SUPER_MANAGER_WRITE)
    public ApiResponse<BranchResponse> activate(@PathVariable Long branchId) {
        return ApiResponse.ok(branches.activateBranch(branchId), "Đã kích hoạt chi nhánh");
    }
}
