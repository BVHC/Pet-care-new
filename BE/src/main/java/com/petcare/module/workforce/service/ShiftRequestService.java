package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.CreateShiftRequestRequest;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;
import com.petcare.module.workforce.fsm.ShiftRequestTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftRequestMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.AssignmentScope;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.RequestOwner;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import lombok.extern.slf4j.Slf4j;

/**
 * Yêu cầu nghỉ / đổi ca của <b>chính nhân viên</b> (UC36, workforce-v1 #9–11; BR-NS-04, BR-NS-05, BR-NS-08; docs/adr/0138
 * bước 6). Danh tính luôn là {@code branchScope.current().accountId()}: không có tham số người gửi nên không gửi, xem hay
 * rút thay người khác được; phân ca / yêu cầu / ca đích không phải của mình trả 404 cùng dạng với "không tồn tại".
 * <p>
 * <b>#9 gửi (một transaction READ COMMITTED, ghi)</b> — đọc không khóa phạm vi (404) → hình dạng LEAVE / SWAP (400) →
 * khóa {@code accounts} của người gọi → phân ca → yêu cầu {@code PENDING} (ADR-0123 mục 8, <b>không</b> khóa ca đích:
 * duyệt kiểm lại, BR-NS-06) → <b>đọc lại dưới khóa</b> rồi guard 400 {@code BR-NS-04} → {@code saveAndFlush} → thông báo
 * outbox cho quản lý. Hai lần gửi đua nhau tuần tự nhờ hai khóa, nên lần hai ra 400 chứ không 409; unique
 * {@code ux_shift_requests_pending} chỉ là lưới thứ hai. Không audit (BR-NS-10).
 * <p>
 * <b>#11 rút</b> — đọc không khóa chủ (404) → {@code accounts} → phân ca → yêu cầu → đọc lại: chuyển trạng thái hợp lệ
 * (409), không có guard 400. Không audit, không thông báo, phân ca giữ nguyên (BR-NS-05, BR-NS-10).
 * <p>
 * <b>#10 danh sách</b> — {@code readOnly} + REPEATABLE_READ để trang, tổng và dữ liệu ghép cùng một ảnh chụp.
 */
@Slf4j
@Service
public class ShiftRequestService {

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final ShiftDefinitionRepository definitions;
    private final ShiftAssignmentRepository assignments;
    private final ShiftRequestRepository requests;
    private final ShiftRequestMapper mapper;
    private final ShiftRequestTransitionHandler transitions;
    private final NotificationApi notifications;
    private final BranchQueryApi branches;
    private final SystemConfigApi configs;
    private final Clock clock;

    public ShiftRequestService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftDefinitionRepository definitions, ShiftAssignmentRepository assignments,
            ShiftRequestRepository requests, ShiftRequestMapper mapper, ShiftRequestTransitionHandler transitions,
            NotificationApi notifications, BranchQueryApi branches, SystemConfigApi configs, Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.definitions = definitions;
        this.assignments = assignments;
        this.requests = requests;
        this.mapper = mapper;
        this.transitions = transitions;
        this.notifications = notifications;
        this.branches = branches;
        this.configs = configs;
        this.clock = clock;
    }

    /** workforce-v1 #9 — nhân viên gửi yêu cầu nghỉ / đổi ca cho phân ca {@code SCHEDULED} của chính mình (Yêu cầu#1). */
    @Transactional
    public ShiftRequestResponse createShiftRequest(CreateShiftRequestRequest request) {
        Long staffId = branchScope.current().accountId();

        // 1. Phân quyền dữ liệu bằng đọc không khóa, trước mọi khóa. Phân ca của đồng nghiệp và phân ca không tồn tại
        //    cùng một dạng 404; không khóa tài khoản của người khác.
        AssignmentScope scope = assignments.findScopeById(request.shiftAssignmentId())
                .filter(found -> staffId.equals(found.getStaffId()))
                .orElseThrow(() -> new ResourceNotFoundException("phân ca", request.shiftAssignmentId()));
        ShiftDefinition target = request.targetShiftDefinitionId() == null ? null
                : definitions.findById(request.targetShiftDefinitionId())
                        .filter(found -> scope.getBranchId().equals(found.getBranchId()))
                        .orElseThrow(() -> new ResourceNotFoundException("ca làm việc",
                                request.targetShiftDefinitionId()));

        // 2. Hình dạng LEAVE / SWAP: không cần DB nên trước khóa. Phải chặn ở đây vì ShiftRequest.submit ném
        //    IllegalArgumentException (500) cho các trường hợp này.
        if (request.type() == ShiftRequestType.SWAP && target == null) {
            throw new BusinessRuleViolationException("BR-NS-04", "Yêu cầu đổi ca phải chọn ca đích");
        }
        if (request.type() == ShiftRequestType.LEAVE && target != null) {
            throw new BusinessRuleViolationException("BR-NS-04", "Yêu cầu nghỉ không có ca đích");
        }

        // 3. Khóa theo ADR-0123 mục 8: accounts → phân ca → yêu cầu PENDING; từ đây mọi đọc là đọc dưới khóa.
        StaffSummary staff = staffDirectory.findStaffForUpdate(staffId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản " + staffId + " không phải nhân viên"));
        ShiftAssignment assignment = assignments.findByIdForUpdate(request.shiftAssignmentId())
                .orElseThrow(() -> new ResourceNotFoundException("phân ca", request.shiftAssignmentId()));
        ShiftRequest pending = requests.findPendingByAssignmentIdForUpdate(assignment.getId()).orElse(null);

        // 4. Guard BR-NS-04 trên dữ liệu đã đọc lại dưới khóa. Phân ca không còn SCHEDULED là 400, không phải 409: đó là
        //    điều kiện tạo Yêu cầu#1 chứ không phải chuyển trạng thái của yêu cầu (workforce-v1 A11).
        if (assignment.getStatus() != ShiftAssignmentStatus.SCHEDULED) {
            throw new BusinessRuleViolationException("BR-NS-04", "Chỉ phân ca đang chờ làm mới được gửi yêu cầu");
        }
        ShiftDefinition current = definitions.findById(assignment.getShiftDefinitionId())
                .orElseThrow(() -> new IllegalStateException("Phân ca " + assignment.getId() + " thiếu ca làm việc "
                        + assignment.getShiftDefinitionId()));
        int leadHours = configs.getInt(ConfigKey.WORKFORCE_REQUEST_LEAD_HOURS);
        Instant now = clock.instant();
        // "ít nhất leadHours giờ": còn đúng leadHours giờ vẫn được gửi. Hạn chỉ tính theo ca hiện tại, kể cả SWAP (D074).
        if (LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).plusHours(leadHours)
                .isAfter(assignment.getWorkDate().atTime(current.getStartTime()))) {
            throw new BusinessRuleViolationException("BR-NS-04",
                    "Phải gửi yêu cầu trước giờ bắt đầu ca ít nhất " + leadHours + " giờ");
        }
        if (pending != null) {
            throw new BusinessRuleViolationException("BR-NS-04", "Phân ca này đã có yêu cầu đang chờ xử lý");
        }
        if (target != null) {
            requireUsableTarget(assignment, current, target);
        }

        // 5. Ghi. saveAndFlush để vi phạm ràng buộc nổi trong service (409) và trước khi enqueue.
        ShiftRequest saved = requests.saveAndFlush(ShiftRequest.submit(assignment.getId(), staffId,
                assignment.getShiftDefinitionId(), request.type(), request.targetShiftDefinitionId(),
                request.reason()));

        // 6. Thông báo (BR-NS-08) dùng đúng lý do đã strip lưu trong yêu cầu.
        notifyManagers(assignment, staff, saved, current);

        return toResponses(List.of(saved)).get(0);
    }

    /** workforce-v1 #10 — yêu cầu của chính người gọi, mới nhất trước. {@code page} < 0 → 0, {@code size} kẹp 1–100. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ShiftRequestResponse> listMyShiftRequests(ShiftRequestStatus status, int page, int size) {
        Long staffId = branchScope.current().accountId();
        Page<ShiftRequest> result = requests.findByRequester(staffId, status, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), ShiftAssignmentService.MAX_PAGE_SIZE)));
        return PageResponse.of(new PageImpl<>(toResponses(result.getContent()), result.getPageable(),
                result.getTotalElements()));
    }

    /** workforce-v1 #11 — người gửi rút yêu cầu còn {@code PENDING} (Yêu cầu#2); phân ca giữ nguyên. */
    @Transactional
    public ShiftRequestResponse withdrawShiftRequest(Long shiftRequestId) {
        Long staffId = branchScope.current().accountId();

        // 1. Phân quyền dữ liệu bằng đọc không khóa: yêu cầu của đồng nghiệp và yêu cầu không tồn tại cùng một dạng 404.
        RequestOwner owner = requests.findOwnerById(shiftRequestId)
                .filter(found -> staffId.equals(found.getRequesterId()))
                .orElseThrow(() -> new ResourceNotFoundException("yêu cầu", shiftRequestId));

        // 2. Khóa accounts → phân ca → yêu cầu (cùng thứ tự với gửi, hủy phân ca và nhân viên rời chi nhánh).
        staffDirectory.findStaffForUpdate(staffId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản " + staffId + " không phải nhân viên"));
        assignments.findByIdForUpdate(owner.getAssignmentId())
                .orElseThrow(() -> new IllegalStateException("Yêu cầu " + shiftRequestId + " thiếu phân ca "
                        + owner.getAssignmentId()));
        ShiftRequest request = requests.findByIdForUpdate(shiftRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("yêu cầu", shiftRequestId));

        // 3. Đọc lại dưới khóa: yêu cầu có thể vừa bị duyệt / hủy / hệ thống đóng trong lúc chờ khóa → 409.
        transitions.validateTransition(request.getStatus(), ShiftRequestStatus.WITHDRAWN);
        request.withdraw(clock.instant());
        return toResponses(List.of(requests.saveAndFlush(request))).get(0);
    }

    /** Ca đích phải đang dùng, khác ca hiện tại và không chồng giờ phân ca <b>khác</b> của người đó trong ngày. */
    private void requireUsableTarget(ShiftAssignment assignment, ShiftDefinition current, ShiftDefinition target) {
        if (!target.isActive()) {
            throw new BusinessRuleViolationException("BR-NS-04", "Ca đích đã ngừng dùng");
        }
        if (target.getId().equals(current.getId())) {
            throw new BusinessRuleViolationException("BR-NS-04", "Ca đích trùng với ca hiện tại");
        }
        // Phân ca đang đổi không tính: đổi sang ca liền kề hay giao một phần với ca hiện tại là hợp lệ vì nó sẽ được thay.
        List<OverlappingShift> overlaps = assignments.findOverlapping(assignment.getStaffId(),
                assignment.getWorkDate(), target.getStartTime(), target.getEndTime()).stream()
                .filter(overlap -> !overlap.getAssignmentId().equals(assignment.getId()))
                .toList();
        if (!overlaps.isEmpty()) {
            throw new BusinessRuleViolationException("BR-NS-04",
                    "Ca đích chồng giờ với phân ca khác trong ngày: " + ShiftAssignmentService.describe(overlaps));
        }
    }

    /**
     * BR-NS-08: BRANCH_MANAGER của chi nhánh; không có ai {@code ACTIVE} thì SUPER_MANAGER (khuôn
     * {@code ShiftNoticeRecipients}, BR-QT-04). Không có người nhận nào thì vẫn commit yêu cầu và chỉ ghi cảnh báo:
     * thông báo không được làm hỏng thao tác chính (BR-NS-08).
     */
    private void notifyManagers(ShiftAssignment assignment, StaffSummary staff, ShiftRequest saved,
            ShiftDefinition current) {
        List<Long> recipients = staffDirectory.findActiveStaffIds(assignment.getBranchId(), Role.BRANCH_MANAGER);
        if (recipients.isEmpty()) {
            recipients = staffDirectory.findActiveSuperManagerIds();
        }
        if (recipients.isEmpty()) {
            log.warn("SHIFT_REQUEST_NO_RECIPIENT: yêu cầu {} của chi nhánh {} không có quản lý để thông báo",
                    saved.getId(), assignment.getBranchId());
            return;
        }
        String branchName = branches.findBranch(assignment.getBranchId()).map(BranchSummary::name)
                .orElseThrow(() -> new IllegalStateException("Phân ca " + assignment.getId() + " thiếu chi nhánh "
                        + assignment.getBranchId()));
        ShiftNotices.submitted(recipients, staff.fullName(), saved.getRequestType(), current.getName(),
                assignment.getWorkDate(), branchName, saved.getReason()).forEach(notifications::enqueue);
    }

    /** Ghép theo lô: đúng một lần cho mỗi nguồn (người gửi, phân ca, ca), bất kể số dòng. */
    private List<ShiftRequestResponse> toResponses(List<ShiftRequest> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> requesterIds = rows.stream().map(ShiftRequest::getRequesterId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> assignmentIds = rows.stream().map(ShiftRequest::getAssignmentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, String> staffNames = new HashMap<>();
        staffDirectory.findStaffByIds(requesterIds).forEach(staff -> staffNames.put(staff.accountId(), staff.fullName()));
        Map<Long, ShiftAssignment> assignmentById = new HashMap<>();
        assignments.findAllById(assignmentIds).forEach(found -> assignmentById.put(found.getId(), found));

        Set<Long> definitionIds = new LinkedHashSet<>();
        assignmentById.values().forEach(found -> definitionIds.add(found.getShiftDefinitionId()));
        rows.stream().map(ShiftRequest::getTargetShiftDefinitionId).filter(Objects::nonNull)
                .forEach(definitionIds::add);
        Map<Long, ShiftDefinition> definitionById = new HashMap<>();
        definitions.findAllById(definitionIds).forEach(found -> definitionById.put(found.getId(), found));

        return rows.stream().map(row -> {
            String requesterName = staffNames.get(row.getRequesterId());
            ShiftAssignment assignment = assignmentById.get(row.getAssignmentId());
            ShiftDefinition currentDefinition = assignment == null ? null
                    : definitionById.get(assignment.getShiftDefinitionId());
            ShiftDefinition targetDefinition = row.getTargetShiftDefinitionId() == null ? null
                    : definitionById.get(row.getTargetShiftDefinitionId());
            if (requesterName == null || assignment == null || currentDefinition == null
                    || (row.getTargetShiftDefinitionId() != null && targetDefinition == null)) {
                // Khóa ngoại + kiểm lúc tạo bảo đảm đủ cả bốn; thiếu là hỏng dữ liệu, không trả null sai hợp đồng.
                throw new IllegalStateException("Yêu cầu " + row.getId() + " thiếu người gửi, phân ca hoặc ca làm việc");
            }
            return mapper.toResponse(row, requesterName, assignment.getWorkDate(), currentDefinition.getName(),
                    targetDefinition == null ? null : targetDefinition.getName());
        }).toList();
    }
}
