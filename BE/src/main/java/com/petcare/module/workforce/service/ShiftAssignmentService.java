package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.PendingRequestRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Phân ca của chi nhánh (UC34, workforce-v1 #4–5; BR-NS-02, BR-NS-03, BR-NS-10; docs/adr/0138 bước 3).
 * <p>
 * <b>#5 tạo (một transaction, READ COMMITTED)</b> — thứ tự cố định: kiểm không khóa nhân viên và ca có thuộc chi nhánh
 * người gọi không (không thì 404, đồng dạng với "không tồn tại" để không lộ id của chi nhánh khác) → ngày ≥ hôm nay →
 * khóa {@code accounts} của nhân viên {@code FOR NO KEY UPDATE} và <b>đọc lại dưới khóa</b> → khóa ca {@code FOR SHARE}
 * → kiểm vai trò / trạng thái / ca đang dùng / chồng giờ → {@code saveAndFlush} → audit. Thứ tự khóa cùng luồng
 * vô hiệu hóa / điều chuyển (accounts → ca → phân ca, docs/adr/0123 mục 8). Phải READ COMMITTED: kiểm sơ bộ chạy trước
 * khóa trong cùng transaction nên ở REPEATABLE_READ truy vấn chồng giờ sau khi chờ khóa sẽ không thấy phân ca vừa
 * commit. Không thông báo (BR-NS-08 không liệt kê phân hộ).
 * <p>
 * <b>#4 danh sách</b> — {@code readOnly} + REPEATABLE_READ để trang, tổng và dữ liệu ghép cùng một ảnh chụp; luôn lọc theo
 * chi nhánh của người gọi (không có tham số chi nhánh).
 */
@Service
public class ShiftAssignmentService {

    /** BR-NS-03, A9: khoảng {@code from}–{@code to} tối đa 62 ngày (tính cả hai đầu). */
    static final int MAX_RANGE_DAYS = 62;
    static final int MAX_PAGE_SIZE = 100;

    private static final Set<Role> SCHEDULABLE_ROLES = Set.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER);
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final ShiftDefinitionRepository definitions;
    private final ShiftAssignmentRepository assignments;
    private final ShiftRequestRepository requests;
    private final ShiftAssignmentMapper mapper;
    private final AuditRecorder audit;
    private final Clock clock;

    public ShiftAssignmentService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftDefinitionRepository definitions, ShiftAssignmentRepository assignments,
            ShiftRequestRepository requests, ShiftAssignmentMapper mapper, AuditRecorder audit, Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.definitions = definitions;
        this.assignments = assignments;
        this.requests = requests;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    /** workforce-v1 #5 — BRANCH_MANAGER phân hộ (BR-NS-02), {@code source = MANAGER}, không kiểm định mức (BR-NS-12). */
    @Transactional
    public ShiftAssignmentResponse createShiftAssignment(CreateShiftAssignmentRequest request) {
        Long branchId = requireBranch(branchScope.current().branchId());

        // 1. Kiểm không khóa: là bước phân quyền dữ liệu, chạy trước mọi khóa.
        staffDirectory.findStaff(request.staffId())
                .filter(staff -> branchId.equals(staff.branchId()))
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", request.staffId()));
        definitions.findBranchIdById(request.shiftDefinitionId())
                .filter(branchId::equals)
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", request.shiftDefinitionId()));

        // 2. Guard thuần Clock, không cần khóa.
        if (request.workDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-NS-02", "Không phân ca cho ngày trong quá khứ");
        }

        // 3. Khóa nhân viên rồi đọc lại: chi nhánh / trạng thái có thể đã đổi giữa kiểm sơ bộ và khóa.
        StaffSummary staff = staffDirectory.findStaffForUpdate(request.staffId())
                .filter(found -> branchId.equals(found.branchId()))
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", request.staffId()));
        if (!SCHEDULABLE_ROLES.contains(staff.role()) || !staff.active()) {
            throw new BusinessRuleViolationException("BR-NS-02",
                    "Chỉ phân ca cho lễ tân, bác sĩ hoặc nhân viên chăm sóc đang hoạt động của chi nhánh");
        }

        // 4. Khóa ca chia sẻ (xung đột với PATCH ngừng dùng) rồi kiểm.
        ShiftDefinition definition = definitions.findByIdForShare(request.shiftDefinitionId())
                .filter(found -> branchId.equals(found.getBranchId()))
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", request.shiftDefinitionId()));
        if (!definition.isActive()) {
            throw new BusinessRuleViolationException("BR-NS-02", "Ca đã ngừng dùng, không phân ca được");
        }
        List<OverlappingShift> overlaps = assignments.findOverlapping(staff.accountId(), request.workDate(),
                definition.getStartTime(), definition.getEndTime());
        if (!overlaps.isEmpty()) {
            throw new BusinessRuleViolationException("BR-NS-02",
                    "Nhân viên đã có phân ca trùng hoặc chồng giờ: " + describe(overlaps));
        }

        ShiftAssignment saved = assignments.saveAndFlush(ShiftAssignment.schedule(branchId, staff.accountId(),
                definition.getId(), request.workDate(), ShiftAssignmentSource.MANAGER));
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_CREATED)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, saved.getId())
                .after(mapper.toSnapshot(saved)));
        return mapper.toResponse(saved, staff.fullName(), definition, null);
    }

    /**
     * workforce-v1 #4 — lịch phân ca của chi nhánh người gọi trong {@code [from, to]}. {@code page} < 0 → 0, {@code size}
     * kẹp 1–100 (khuôn {@code MyNotificationService}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ShiftAssignmentResponse> listShiftAssignments(LocalDate from, LocalDate to, Long staffId,
            ShiftAssignmentStatus status, int page, int size) {
        Long branchId = requireBranch(branchScope.current().branchId());
        if (to.isBefore(from)) {
            throw new BusinessRuleViolationException("BR-NS-03", "Ngày kết thúc phải từ ngày bắt đầu trở đi");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessRuleViolationException("BR-NS-03",
                    "Khoảng ngày tối đa " + MAX_RANGE_DAYS + " ngày");
        }
        Page<ShiftAssignment> result = assignments.findBranchSchedule(branchId, from, to, staffId, status,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
        List<ShiftAssignmentResponse> content = toResponses(result.getContent());
        return PageResponse.of(new PageImpl<>(content, result.getPageable(), result.getTotalElements()));
    }

    /** Ghép theo lô: đúng một lần cho mỗi nguồn (nhân viên, ca, yêu cầu chờ), bất kể số dòng. */
    private List<ShiftAssignmentResponse> toResponses(List<ShiftAssignment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> staffIds = rows.stream().map(ShiftAssignment::getStaffId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> definitionIds = rows.stream().map(ShiftAssignment::getShiftDefinitionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> assignmentIds = rows.stream().map(ShiftAssignment::getId).toList();

        Map<Long, String> staffNames = new HashMap<>();
        staffDirectory.findStaffByIds(staffIds).forEach(staff -> staffNames.put(staff.accountId(), staff.fullName()));
        Map<Long, ShiftDefinition> definitionById = new HashMap<>();
        definitions.findAllById(definitionIds).forEach(definition -> definitionById.put(definition.getId(), definition));
        Map<Long, Long> pendingByAssignment = new HashMap<>();
        for (PendingRequestRow row : requests.findPendingByAssignmentIds(assignmentIds)) {
            pendingByAssignment.put(row.getAssignmentId(), row.getRequestId());
        }

        List<ShiftAssignmentResponse> responses = new ArrayList<>(rows.size());
        for (ShiftAssignment row : rows) {
            String staffName = staffNames.get(row.getStaffId());
            ShiftDefinition definition = definitionById.get(row.getShiftDefinitionId());
            if (staffName == null || definition == null) {
                // Khóa ngoại + kiểm lúc tạo bảo đảm cả hai luôn có; thiếu là hỏng dữ liệu, không trả null sai hợp đồng.
                throw new IllegalStateException("Phân ca " + row.getId() + " thiếu nhân viên hoặc ca làm việc");
            }
            responses.add(mapper.toResponse(row, staffName, definition, pendingByAssignment.get(row.getId())));
        }
        return responses;
    }

    private static String describe(List<OverlappingShift> overlaps) {
        return overlaps.stream()
                .map(o -> o.getShiftName() + " (" + o.getStartTime().format(HOUR_MINUTE) + "–"
                        + o.getEndTime().format(HOUR_MINUTE) + ")")
                .collect(Collectors.joining(", "));
    }

    /** Lớp phòng thủ thứ hai sau {@code @PreAuthorize}: không có đường "toàn chuỗi" cho phân ca (docs/adr/0138). */
    private static Long requireBranch(Long branchId) {
        if (branchId == null) {
            throw new AccessDeniedScopeException("chi nhánh của người gọi", "toàn chuỗi");
        }
        return branchId;
    }
}
