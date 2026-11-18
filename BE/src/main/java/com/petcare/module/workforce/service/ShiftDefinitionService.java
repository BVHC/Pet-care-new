package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.workforce.dto.CreateShiftDefinitionRequest;
import com.petcare.module.workforce.dto.ShiftDefinitionAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftDefinitionResponse;
import com.petcare.module.workforce.dto.UpdateShiftDefinitionRequest;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftDefinitionMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Ca làm việc của chi nhánh (UC34, workforce-v1 #1–3; BR-NS-01, BR-NS-10; docs/adr/0138). Mỗi use case một
 * transaction. {@code PATCH}: đọc {@code branch_id} không khóa (truy vấn scalar, không nạp entity) → phân quyền chi
 * nhánh → khóa dòng ca ({@code FOR NO KEY UPDATE}) → guard dưới khóa → {@code saveAndFlush} → audit. Tạo ca chỉ dựa vào
 * kiểm tên trước và unique index {@code lower(name)} khi đua (409). Không thông báo (BR-NS-08 không áp cho ca).
 */
@Service
public class ShiftDefinitionService {

    private final BranchScope branchScope;
    private final ShiftDefinitionRepository definitions;
    private final ShiftAssignmentRepository assignments;
    private final ShiftDefinitionMapper mapper;
    private final AuditRecorder audit;
    private final Clock clock;

    public ShiftDefinitionService(BranchScope branchScope, ShiftDefinitionRepository definitions,
            ShiftAssignmentRepository assignments, ShiftDefinitionMapper mapper, AuditRecorder audit, Clock clock) {
        this.branchScope = branchScope;
        this.definitions = definitions;
        this.assignments = assignments;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * workforce-v1 #1. BRANCH_MANAGER thấy cả ca ngừng dùng (trừ khi gửi {@code activeOnly = true}); A06–A08 chỉ thấy ca
     * đang dùng và {@code activeOnly} bị bỏ qua (A10).
     */
    @Transactional(readOnly = true)
    public List<ShiftDefinitionResponse> listShiftDefinitions(Boolean activeOnly) {
        SecurityPrincipal principal = branchScope.current();
        Long branchId = requireBranch(branchScope.resolve(null));
        boolean onlyActive = !Role.BRANCH_MANAGER.name().equals(principal.role()) || Boolean.TRUE.equals(activeOnly);
        return mapper.toResponses(definitions.findByBranch(branchId, onlyActive));
    }

    /** workforce-v1 #2 — tạo ca cho chi nhánh của người gọi (BR-NS-01, BR-NS-10). */
    @Transactional
    public ShiftDefinitionResponse createShiftDefinition(CreateShiftDefinitionRequest request) {
        Long branchId = requireBranch(branchScope.current().branchId());
        String name = request.name().strip();
        requireTimeOrder(request.startTime(), request.endTime());
        if (definitions.existsByBranchAndName(branchId, name, null)) {
            throw new BusinessRuleViolationException("BR-NS-01", "Tên ca đã tồn tại trong chi nhánh");
        }
        ShiftDefinition saved = definitions.saveAndFlush(
                ShiftDefinition.create(branchId, name, request.startTime(), request.endTime()));
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_DEFINITION_CREATED)
                .entity(WorkforceAuditActions.SHIFT_DEFINITION_ENTITY, saved.getId())
                .after(mapper.toSnapshot(saved)));
        return mapper.toResponse(saved);
    }

    /**
     * workforce-v1 #3 — sửa tên / giờ, ngừng dùng hoặc bật lại ca (BR-NS-01, BR-NS-10). Trường vắng giữ nguyên; không có
     * gì đổi thì không ghi và không audit.
     */
    @Transactional
    public ShiftDefinitionResponse updateShiftDefinition(Long shiftDefinitionId, UpdateShiftDefinitionRequest request) {
        Long branchId = definitions.findBranchIdById(shiftDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", shiftDefinitionId));
        branchScope.check(branchId);
        ShiftDefinition definition = definitions.findByIdForUpdate(shiftDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", shiftDefinitionId));

        String newName = request.name() == null ? definition.getName() : request.name().strip();
        LocalTime newStart = request.startTime() == null ? definition.getStartTime() : request.startTime();
        LocalTime newEnd = request.endTime() == null ? definition.getEndTime() : request.endTime();
        boolean newActive = request.active() == null ? definition.isActive() : request.active();

        requireTimeOrder(newStart, newEnd);
        boolean renamed = !newName.equals(definition.getName());
        boolean retimed = !newStart.equals(definition.getStartTime()) || !newEnd.equals(definition.getEndTime());
        boolean deactivating = definition.isActive() && !newActive;
        boolean activating = !definition.isActive() && newActive;

        if (renamed && definitions.existsByBranchAndName(branchId, newName, shiftDefinitionId)) {
            throw new BusinessRuleViolationException("BR-NS-01", "Tên ca đã tồn tại trong chi nhánh");
        }
        if (retimed && assignments.existsAnyForDefinition(branchId, shiftDefinitionId)) {
            throw new BusinessRuleViolationException("BR-NS-01",
                    "Ca đã có phân ca, không đổi được giờ; hãy tạo ca mới");
        }
        if (deactivating && assignments.countScheduledFrom(shiftDefinitionId, LocalDate.now(clock)) > 0) {
            throw new BusinessRuleViolationException("BR-NS-01",
                    "Ca còn phân ca từ hôm nay trở đi, không ngừng dùng được");
        }
        if (!renamed && !retimed && !deactivating && !activating) {
            return mapper.toResponse(definition);
        }

        // Chụp trước khi đổi: entity là đối tượng sống nên chụp sau sẽ ra before == after.
        ShiftDefinitionAuditSnapshot before = mapper.toSnapshot(definition);
        if (renamed) {
            definition.rename(newName);
        }
        if (retimed) {
            definition.changeTimes(newStart, newEnd);
        }
        if (deactivating) {
            definition.deactivate();
        } else if (activating) {
            definition.activate();
        }
        ShiftDefinition saved = definitions.saveAndFlush(definition);
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_DEFINITION_UPDATED)
                .entity(WorkforceAuditActions.SHIFT_DEFINITION_ENTITY, saved.getId())
                .before(before).after(mapper.toSnapshot(saved)));
        return mapper.toResponse(saved);
    }

    /** Lớp phòng thủ thứ hai sau {@code @PreAuthorize}: không có đường "toàn chuỗi" cho ca (docs/adr/0138). */
    private static Long requireBranch(Long branchId) {
        if (branchId == null) {
            throw new AccessDeniedScopeException("chi nhánh của người gọi", "toàn chuỗi");
        }
        return branchId;
    }

    private static void requireTimeOrder(LocalTime start, LocalTime end) {
        if (!start.isBefore(end)) {
            throw new BusinessRuleViolationException("BR-NS-01", "Giờ bắt đầu phải nhỏ hơn giờ kết thúc");
        }
    }
}
