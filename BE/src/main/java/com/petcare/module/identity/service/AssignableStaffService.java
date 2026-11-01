package com.petcare.module.identity.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.dto.AssignableRole;
import com.petcare.module.identity.dto.AssignableStaffResponse;
import com.petcare.module.identity.exception.BranchSelectionRequiredException;
import com.petcare.module.identity.mapper.AssignableStaffMapper;
import com.petcare.platform.security.BranchScope;

/**
 * UC45 — nhân viên có thể gán lượt ({@code GET /api/staff/assignable}, identity-v1 #38; BR-TN-05, 06; docs/adr/0089).
 * Danh sách đã lọc và sắp xếp nằm ở {@link StaffDirectoryApi#findAssignableStaff} (ACTIVE, không bị khóa, đúng chi nhánh
 * và chức vụ; online trước, rồi họ tên, rồi {@code accountId}) — cùng nguồn với kiểm tra của lệnh gán, nên không thêm
 * truy vấn mới. Thứ tự: phạm vi chi nhánh ({@link BranchScope#resolve}: A05, A06 chỉ chi nhánh mình, chi nhánh khác →
 * 403) rồi mới đến "SUPER_MANAGER chưa chọn chi nhánh" → 400, <b>trước</b> khi gọi API vì nó từ chối {@code branchId}
 * null. Chi nhánh không tồn tại → mảng rỗng, không 404 (không để dò mã chi nhánh). Chỉ đọc, không khóa, không audit:
 * danh sách là ảnh chụp, lệnh gán kiểm lại dưới khóa (docs/adr/0061).
 */
@Service
@Transactional(readOnly = true)
public class AssignableStaffService {

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final AssignableStaffMapper mapper;

    public AssignableStaffService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            AssignableStaffMapper mapper) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.mapper = mapper;
    }

    public List<AssignableStaffResponse> listAssignable(AssignableRole role, Long branchId) {
        Long effectiveBranchId = branchScope.resolve(branchId);
        if (effectiveBranchId == null) {
            throw new BranchSelectionRequiredException();
        }
        return staffDirectory.findAssignableStaff(effectiveBranchId, role.role()).stream()
                .map(mapper::toResponse)
                .toList();
    }
}
