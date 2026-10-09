package com.petcare.module.branch.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.dto.BranchResponse;
import com.petcare.module.branch.dto.CreateBranchRequest;
import com.petcare.module.branch.dto.UpdateBranchRequest;
import com.petcare.module.branch.entity.Branch;
import com.petcare.module.branch.entity.BranchStatus;
import com.petcare.module.branch.fsm.BranchTransitionHandler;
import com.petcare.module.branch.mapper.BranchMapper;
import com.petcare.module.branch.repository.BranchRepository;
import com.petcare.module.branch.repository.OpeningHoursRepository;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

import jakarta.persistence.criteria.Predicate;

/** UC12 — tạo, cập nhật, kích hoạt chi nhánh, bật cờ nhận cấp cứu ngoài giờ (BR-CN-01, BR-CN-05, BR-QT-04). */
@Service
public class BranchService {

    private final BranchRepository branches;
    private final OpeningHoursRepository openingHours;
    private final StaffDirectoryApi staff;
    private final BranchTransitionHandler transitions;
    private final BranchScope scope;
    private final BranchMapper mapper;
    private final Clock clock;

    public BranchService(BranchRepository branches, OpeningHoursRepository openingHours, StaffDirectoryApi staff,
            BranchTransitionHandler transitions, BranchScope scope, BranchMapper mapper, Clock clock) {
        this.branches = branches;
        this.openingHours = openingHours;
        this.staff = staff;
        this.transitions = transitions;
        this.scope = scope;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** SUPER_MANAGER, ADMIN xem cả chuỗi; nhân viên chi nhánh chỉ thấy chi nhánh của mình. */
    @Transactional(readOnly = true)
    public List<BranchResponse> listBranches(BranchStatus status) {
        Long ownBranch = scope.resolve(null);
        Specification<Branch> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (ownBranch != null) {
                predicates.add(cb.equal(root.get("id"), ownBranch));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return branches.findAll(spec, Sort.by("name")).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BranchResponse getBranch(Long branchId) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        scope.check(branch.getId());
        return mapper.toResponse(branch);
    }

    /** Chi nhánh#1: — → DRAFT. */
    @Transactional
    public BranchResponse createBranch(CreateBranchRequest request) {
        String name = request.name().trim();
        if (branches.existsByName(name)) {
            throw new BusinessRuleViolationException("BR-CN-01", "Tên chi nhánh đã tồn tại");
        }
        Branch branch = new Branch(name, request.address().trim(), request.phone().trim(), request.latitude(),
                request.longitude(), Boolean.TRUE.equals(request.acceptsAfterHoursEmergency()));
        transitions.validateInitial(BranchStatus.DRAFT);
        return mapper.toResponse(branches.save(branch));
    }

    @Transactional
    public BranchResponse updateBranch(Long branchId, UpdateBranchRequest request) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> notFound(branchId));
        if (request.name() != null) {
            String name = request.name().trim();
            if (branches.existsByNameAndIdNot(name, branchId)) {
                throw new BusinessRuleViolationException("BR-CN-01", "Tên chi nhánh đã tồn tại");
            }
            branch.setName(name);
        }
        if (request.address() != null) {
            branch.setAddress(request.address().trim());
        }
        if (request.phone() != null) {
            branch.setPhone(request.phone().trim());
        }
        if (request.latitude() != null) {
            branch.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            branch.setLongitude(request.longitude());
        }
        if (request.acceptsAfterHoursEmergency() != null) {
            branch.setAcceptsAfterHoursEmergency(request.acceptsAfterHoursEmergency());
        }
        return mapper.toResponse(branches.saveAndFlush(branch));
    }

    /**
     * Chi nhánh#2: DRAFT → ACTIVE (BR-CN-01, BR-QT-04). Khóa dòng chi nhánh để hai yêu cầu kích hoạt đồng thời chỉ
     * một bên thắng. Chi nhánh đã {@code ACTIVE} bị từ chối 409 trước khi kiểm điều kiện: không có guard nghiệp vụ nào
     * cần báo trước trạng thái, và kiểm điều kiện còn gọi sang module khác.
     */
    @Transactional
    public BranchResponse activateBranch(Long branchId) {
        Branch branch = branches.findByIdForUpdate(branchId).orElseThrow(() -> notFound(branchId));
        transitions.validateTransition(branch.getStatus(), BranchStatus.ACTIVE);

        boolean noManager = staff.countBranchManagers(branchId) < 1;
        boolean noHours = !openingHours.existsByBranchIdAndOpen1IsNotNull(branchId);
        if (noManager || noHours) {
            List<String> missing = new ArrayList<>();
            if (noManager) {
                missing.add("chưa có BRANCH_MANAGER");
            }
            if (noHours) {
                missing.add("chưa cấu hình giờ mở cửa");
            }
            throw new BusinessRuleViolationException(noManager ? "BR-QT-04" : "BR-CN-01",
                    "Không kích hoạt được chi nhánh: " + String.join(", ", missing));
        }

        branch.activate(Instant.now(clock));
        return mapper.toResponse(branches.saveAndFlush(branch));
    }

    private static ResourceNotFoundException notFound(Long branchId) {
        return new ResourceNotFoundException("Chi nhánh", branchId);
    }
}
