package com.petcare.module.identity.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.dto.PublicVetResponse;
import com.petcare.module.identity.mapper.PublicVetMapper;

/**
 * UC15 — đội ngũ bác sĩ công khai ({@code GET /api/public/vets}, identity-v1 #37; BR-TK-20, BR-CK-01; docs/adr/0026,
 * thay ADR-0024 mục 5). VET "công khai" = {@link StaffDirectoryApi#listPublicVets()} ({@code ACTIVE}, không bị khóa —
 * định nghĩa chỉ nằm một chỗ), thuộc chi nhánh {@code ACTIVE} của {@link BranchQueryApi#listActiveBranches()}. VET
 * không gắn chi nhánh hoặc ở chi nhánh {@code DRAFT} bị loại; {@code branchId} không phải chi nhánh {@code ACTIVE} →
 * danh sách rỗng (chi nhánh chưa hoạt động vô hình với trang công khai). Lọc chi nhánh trong bộ nhớ: số VET toàn chuỗi
 * nhỏ, không có index {@code staff_profiles(branch_id)} (ADR-0024). Thứ tự giữ theo câu query: họ tên, rồi id.
 */
@Service
public class PublicVetService {

    private final StaffDirectoryApi staffDirectory;
    private final BranchQueryApi branches;
    private final PublicVetMapper mapper;

    public PublicVetService(StaffDirectoryApi staffDirectory, BranchQueryApi branches, PublicVetMapper mapper) {
        this.staffDirectory = staffDirectory;
        this.branches = branches;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<PublicVetResponse> listPublicVets(Long branchId) {
        Map<Long, String> activeBranchNames = branches.listActiveBranches().stream()
                .collect(Collectors.toMap(BranchSummary::branchId, BranchSummary::name));
        if (branchId != null && !activeBranchNames.containsKey(branchId)) {
            return List.of();
        }
        return staffDirectory.listPublicVets().stream()
                .filter(vet -> vet.branchId() != null && activeBranchNames.containsKey(vet.branchId()))
                .filter(vet -> branchId == null || branchId.equals(vet.branchId()))
                .map(vet -> mapper.toPublicVetResponse(vet, activeBranchNames.get(vet.branchId())))
                .toList();
    }
}
