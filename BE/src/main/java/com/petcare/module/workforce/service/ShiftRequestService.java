package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
import com.petcare.module.workforce.dto.RejectShiftRequestRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftRequestAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.fsm.ShiftRequestTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.mapper.ShiftRequestMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.AssignmentScope;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.RequestOwner;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import lombok.extern.slf4j.Slf4j;

/**
 * Yêu cầu nghỉ / đổi ca của <b>chính nhân viên</b> (UC36, workforce-v1 #9–11; BR-NS-04, BR-NS-05, BR-NS-08; docs/adr/0149
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
 * <p>
 * <b>#12 danh sách của chi nhánh (BRANCH_MANAGER)</b> — cùng khuôn #10: không khóa, không ghi, không audit; chi nhánh
 * của <b>phân ca</b> nằm trong SQL; {@code status} vắng = {@code PENDING}, cũ nhất trước (docs/adr/0149 bước 8).
 * <p>
 * <b>#14 từ chối (BRANCH_MANAGER)</b> — cùng khuôn khóa #6 / #7: phạm vi không khóa → {@code accounts} → phân ca → yêu
 * cầu → đọc lại → 409 → ghi yêu cầu + audit + outbox; phân ca không đổi (docs/adr/0150).
 * <p>
 * <b>#13 duyệt (BRANCH_MANAGER)</b> — cùng khuôn khóa #14 (dùng chung {@code lockForDecision}); {@code SWAP} khóa thêm
 * ca đích {@code FOR SHARE} ngay sau {@code accounts} (thứ tự {@code accounts} → ca → phân ca → yêu cầu, ADR-0150 mục
 * 18); thêm 409 của phân ca và guard ca chưa bắt đầu (400). {@code LEAVE}: yêu cầu → {@code APPROVED} + phân ca →
 * {@code ON_LEAVE}; {@code SWAP}: kiểm lại ca đích (chưa bắt đầu, còn dùng, không chồng giờ; 400 {@code BR-NS-06}) rồi
 * phân ca đổi sang ca đích (Phân ca#5); mỗi loại hai audit, hai thông báo (docs/adr/0150).
 * <p>
 * {@code currentShiftName} của mọi response là ca <b>lúc gửi</b> ({@code shift_requests.current_shift_definition_id},
 * V60), không phải ca đang gắn với phân ca: sau khi duyệt {@code SWAP} hai ca này khác nhau.
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
    private final ShiftAssignmentMapper assignmentMapper;
    private final ShiftAssignmentTransitionHandler assignmentTransitions;
    private final NotificationApi notifications;
    private final BranchQueryApi branches;
    private final AuditRecorder audit;
    private final SystemConfigApi configs;
    private final Clock clock;

    public ShiftRequestService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftDefinitionRepository definitions, ShiftAssignmentRepository assignments,
            ShiftRequestRepository requests, ShiftRequestMapper mapper, ShiftRequestTransitionHandler transitions,
            ShiftAssignmentMapper assignmentMapper, ShiftAssignmentTransitionHandler assignmentTransitions,
            NotificationApi notifications, BranchQueryApi branches, AuditRecorder audit, SystemConfigApi configs,
            Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.definitions = definitions;
        this.assignments = assignments;
        this.requests = requests;
        this.mapper = mapper;
        this.transitions = transitions;
        this.assignmentMapper = assignmentMapper;
        this.assignmentTransitions = assignmentTransitions;
        this.notifications = notifications;
        this.branches = branches;
        this.audit = audit;
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
            checkTarget(assignment, current, target, "BR-NS-04");
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
        Page<ShiftRequest> result = requests.findByRequester(staffId, status,
                ShiftAssignmentService.pageOf(page, size));
        return PageResponse.of(new PageImpl<>(toResponses(result.getContent()), result.getPageable(),
                result.getTotalElements()));
    }

    /**
     * workforce-v1 #12 — yêu cầu của chi nhánh người gọi (BRANCH_MANAGER), cũ nhất trước; {@code status} vắng =
     * {@code PENDING} (hàng chờ duyệt). Chi nhánh lấy từ principal, không có tham số chi nhánh; điều kiện nằm trong SQL
     * nên không cần {@code BranchScope.check} từng dòng (sẽ 403 oan với yêu cầu của người đã điều chuyển).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ShiftRequestResponse> listBranchShiftRequests(ShiftRequestStatus status, int page, int size) {
        Long branchId = ShiftAssignmentService.requireBranch(branchScope.current().branchId());
        ShiftRequestStatus effective = status == null ? ShiftRequestStatus.PENDING : status;
        Page<ShiftRequest> result = requests.findByBranch(branchId, effective,
                ShiftAssignmentService.pageOf(page, size));
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

    /**
     * workforce-v1 #14 — BRANCH_MANAGER từ chối yêu cầu {@code PENDING} của chi nhánh, kèm lý do (Yêu cầu#4, BR-NS-06,
     * BR-NS-08, BR-NS-10; UC37). Chỉ đổi yêu cầu; <b>phân ca giữ nguyên</b>. Thứ tự lỗi theo contract §D.6 / A11: phạm vi
     * (404 / 403) trước khóa; sau khóa thì chuyển trạng thái (409). Không có guard 400 nghiệp vụ nào sau 409: đặc tả
     * không đặt điều kiện giờ hay trạng thái phân ca cho từ chối (yêu cầu còn treo trên ca đã qua vẫn từ chối được, đó
     * là cách dọn hàng chờ). Hệ quả (yêu cầu, audit, outbox) cùng một transaction READ COMMITTED; lỗi ở bất kỳ bước nào
     * rút cả ba (docs/adr/0150).
     */
    @Transactional
    public ShiftRequestResponse rejectShiftRequest(Long shiftRequestId, RejectShiftRequestRequest body) {
        // 0–8. Phạm vi (không khóa) → khóa accounts → phân ca → yêu cầu → đọc lại, chuyển trạng thái (409) → nạp ca.
        //      Từ chối không khóa ca đích: không ghi gì vào phân ca nên không cần (ADR-0150 mục 18).
        LockedRequest locked = lockForDecision(shiftRequestId, ShiftRequestStatus.REJECTED, false);
        ShiftRequest request = locked.request();
        ShiftAssignment assignment = locked.assignment();
        StaffSummary requester = locked.requester();

        // 9–10. Ghi. before chụp trước khi đổi vì entity là đối tượng sống; mốc giờ cắt về micro giây để POST = GET.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ShiftRequestAuditSnapshot before = mapper.toSnapshot(request);
        request.reject(branchScope.current().accountId(), body.decisionNote(), now);
        ShiftRequest saved = requests.saveAndFlush(request);

        // 11. Audit (BR-NS-10) dùng đúng lý do đã strip lưu trong yêu cầu.
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_REQUEST_REJECTED)
                .entity(WorkforceAuditActions.SHIFT_REQUEST_ENTITY, saved.getId())
                .before(before).after(mapper.toSnapshot(saved)).reason(saved.getDecisionNote()));

        // 12. Thông báo nhân viên (BR-NS-08); chi nhánh là của phân ca (người gửi có thể đã điều chuyển).
        String branchName = branchNameOf(assignment);
        String description = ShiftNotices.describeRequest(saved.getRequestType(), locked.current().getName(),
                locked.target() == null ? null : locked.target().getName(), assignment.getWorkDate());
        ShiftNotices.rejected(requester.accountId(), staffDirectory.findEmail(requester.accountId()).orElse(null),
                requester.fullName(), description, branchName, saved.getDecisionNote())
                .forEach(notifications::enqueue);

        // 13.
        return toResponses(List.of(saved)).get(0);
    }

    /**
     * workforce-v1 #13 — BRANCH_MANAGER duyệt yêu cầu {@code PENDING} của chi nhánh (Yêu cầu#3, BR-NS-06, BR-NS-08,
     * BR-NS-10; UC37). Không có body. Cùng khuôn khóa với từ chối (dùng chung {@link #lockForDecision}): phạm vi (404 /
     * 403) trước khóa; sau khóa thì chuyển trạng thái của yêu cầu (409) rồi của phân ca (409, lớp phòng thủ: phân ca của
     * yêu cầu {@code PENDING} luôn còn {@code SCHEDULED}, docs/adr/0150 mục 3) và mới đến guard "ca chưa bắt đầu" (400
     * {@code BR-NS-06}). Không kiểm lại hạn 24 giờ (D074a) và không kiểm định mức tuần (BR-NS-12).
     * <p>
     * {@code LEAVE}: yêu cầu → {@code APPROVED} và phân ca → {@code ON_LEAVE} (Phân ca#3). {@code SWAP} (N3-S5b,
     * ADR-0150 mục 17–22): ca đích đã khóa {@code FOR SHARE} trong {@code lockForDecision}; thêm ba guard 400 {@code
     * BR-NS-06} theo thứ tự ca đích đã bắt đầu → ngừng dùng → chồng giờ phân ca khác, rồi phân ca đổi sang ca đích
     * (Phân ca#5, vẫn {@code SCHEDULED}). Mỗi loại: một transaction READ COMMITTED, hai dòng audit, hai thông báo; lỗi ở
     * bất kỳ bước nào rút tất cả.
     */
    @Transactional
    public ShiftRequestResponse approveShiftRequest(Long shiftRequestId) {
        // 0–8. Phạm vi (không khóa) → khóa accounts → [ca đích FOR SHARE] → phân ca → yêu cầu → đọc lại, chuyển trạng
        //      thái (409) → nạp ca.
        LockedRequest locked = lockForDecision(shiftRequestId, ShiftRequestStatus.APPROVED, true);
        ShiftRequest request = locked.request();
        ShiftAssignment assignment = locked.assignment();
        ShiftRequestType type = request.getRequestType();

        // 9. Chuyển trạng thái của phân ca (409) trước mọi guard 400 (A11). LEAVE → ON_LEAVE (#3), SWAP → SCHEDULED (#5).
        assignmentTransitions.validateTransition(assignment.getStatus(), switch (type) {
            case LEAVE -> ShiftAssignmentStatus.ON_LEAVE;
            case SWAP -> ShiftAssignmentStatus.SCHEDULED;
        });

        // 10. Guard BR-NS-06: ca chưa bắt đầu (chung cho LEAVE và SWAP). Một mốc giờ cho mọi dòng ghi, cắt micro giây
        //     để POST = GET.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (ShiftAssignmentService.hasStarted(now, assignment.getWorkDate(), locked.current().getStartTime())) {
            throw new BusinessRuleViolationException("BR-NS-06", "Ca đã bắt đầu, không duyệt được");
        }

        return switch (type) {
            case LEAVE -> approveLeave(locked, now);
            case SWAP -> approveSwap(locked, now);
        };
    }

    /** Phần ghi của duyệt {@code LEAVE}: phân ca rồi yêu cầu (khuôn #6), hai audit, hai thông báo. */
    private ShiftRequestResponse approveLeave(LockedRequest locked, Instant now) {
        ShiftRequest request = locked.request();
        ShiftAssignment assignment = locked.assignment();
        StaffSummary requester = locked.requester();
        Long managerId = branchScope.current().accountId();

        // 11. Ghi. before chụp trước khi đổi vì entity là đối tượng sống; lý do của phân ca chép từ yêu cầu (ADR-0149).
        ShiftAssignmentAuditSnapshot assignmentBefore = assignmentMapper.toSnapshot(assignment);
        ShiftRequestAuditSnapshot requestBefore = mapper.toSnapshot(request);
        assignment.markOnLeave(request.getReason(), managerId, now);
        ShiftAssignment savedAssignment = assignments.saveAndFlush(assignment);
        request.approve(managerId, now);
        ShiftRequest saved = requests.saveAndFlush(request);

        // 12. Audit (BR-NS-10): một dòng cho mỗi đối tượng đổi trạng thái; lý do là lý do của yêu cầu.
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_ON_LEAVE)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, savedAssignment.getId())
                .before(assignmentBefore).after(assignmentMapper.toSnapshot(savedAssignment))
                .reason(savedAssignment.getReason()));
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_REQUEST_APPROVED)
                .entity(WorkforceAuditActions.SHIFT_REQUEST_ENTITY, saved.getId())
                .before(requestBefore).after(mapper.toSnapshot(saved)).reason(saved.getReason()));

        // 13. Thông báo nhân viên (BR-NS-08); chi nhánh là của phân ca (người gửi có thể đã điều chuyển).
        String description = ShiftNotices.describeRequest(saved.getRequestType(), locked.current().getName(), null,
                assignment.getWorkDate());
        ShiftNotices.approved(requester.accountId(), staffDirectory.findEmail(requester.accountId()).orElse(null),
                requester.fullName(), description, branchNameOf(assignment)).forEach(notifications::enqueue);

        // 14.
        return toResponses(List.of(saved)).get(0);
    }

    /**
     * Duyệt {@code SWAP} (Phân ca#5, BR-NS-06, ADR-0150 mục 17–22): guard ca đích trên dữ liệu đọc dưới khóa (ca đích đã
     * khóa {@code FOR SHARE}, nhân viên đã khóa {@code accounts}), rồi phân ca đổi sang ca đích và yêu cầu →
     * {@code APPROVED}, hai audit, hai thông báo — khuôn {@link #approveLeave}.
     */
    private ShiftRequestResponse approveSwap(LockedRequest locked, Instant now) {
        ShiftRequest request = locked.request();
        ShiftAssignment assignment = locked.assignment();
        ShiftDefinition current = locked.current();
        ShiftDefinition target = Objects.requireNonNull(locked.target(), "target");
        StaffSummary requester = locked.requester();
        Long managerId = branchScope.current().accountId();

        // 11. Guard BR-NS-06 cho ca đích. Ca đích sớm hơn ca hiện tại có thể đã bắt đầu dù ca hiện tại chưa (ADR-0150
        //     mục 19): duyệt khi đó là xếp nhân viên vào ca đang chạy. Rồi còn dùng / không chồng giờ (BR-NS-02).
        if (ShiftAssignmentService.hasStarted(now, assignment.getWorkDate(), target.getStartTime())) {
            throw new BusinessRuleViolationException("BR-NS-06", "Ca đích đã bắt đầu, không duyệt được");
        }
        checkTarget(assignment, current, target, "BR-NS-06");

        // 12. Ghi. before chụp trước khi đổi vì entity là đối tượng sống; phân ca trước, yêu cầu sau (khuôn LEAVE).
        ShiftAssignmentAuditSnapshot assignmentBefore = assignmentMapper.toSnapshot(assignment);
        ShiftRequestAuditSnapshot requestBefore = mapper.toSnapshot(request);
        assignment.swapTo(target.getId());
        ShiftAssignment savedAssignment = assignments.saveAndFlush(assignment);
        request.approve(managerId, now);
        ShiftRequest saved = requests.saveAndFlush(request);

        // 13. Audit (BR-NS-10): một dòng cho mỗi đối tượng đổi; lý do là lý do của yêu cầu.
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_SWAPPED)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, savedAssignment.getId())
                .before(assignmentBefore).after(assignmentMapper.toSnapshot(savedAssignment))
                .reason(saved.getReason()));
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_REQUEST_APPROVED)
                .entity(WorkforceAuditActions.SHIFT_REQUEST_ENTITY, saved.getId())
                .before(requestBefore).after(mapper.toSnapshot(saved)).reason(saved.getReason()));

        // 14. Thông báo nhân viên (BR-NS-08): "đổi ca A sang ca B ngày …"; A là ca nạp trước khi đổi.
        String description = ShiftNotices.describeRequest(saved.getRequestType(), current.getName(), target.getName(),
                assignment.getWorkDate());
        ShiftNotices.approved(requester.accountId(), staffDirectory.findEmail(requester.accountId()).orElse(null),
                requester.fullName(), description, branchNameOf(assignment)).forEach(notifications::enqueue);

        // 15.
        return toResponses(List.of(saved)).get(0);
    }

    /** Tên chi nhánh của <b>phân ca</b> (người gửi có thể đã điều chuyển sang chi nhánh khác). */
    private String branchNameOf(ShiftAssignment assignment) {
        return branches.findBranch(assignment.getBranchId()).map(BranchSummary::name)
                .orElseThrow(() -> new IllegalStateException("Phân ca " + assignment.getId() + " thiếu chi nhánh "
                        + assignment.getBranchId()));
    }

    /**
     * Bước chung của quyết định của quản lý (từ chối #14; duyệt #13 dùng lại), <b>không</b> có {@code @Transactional}
     * riêng: chạy trong transaction của hàm gọi. Thứ tự cố định (ADR-0123 mục 8): lớp phòng thủ thứ hai sau
     * {@code @PreAuthorize} (người gọi không có chi nhánh bị từ chối) → phân quyền dữ liệu bằng <b>đọc không khóa</b>
     * (404 yêu cầu; 403 nếu <b>phân ca</b> của nó thuộc chi nhánh khác, không khóa dòng tài khoản của chi nhánh khác)
     * → khóa {@code accounts} của người gửi → [{@code lockTarget}: ca đích {@code FOR SHARE}] → phân ca → yêu cầu; từ đây
     * mọi đọc là đọc dưới khóa → chuyển trạng thái hợp lệ (409) <b>trước</b> mọi guard nghiệp vụ (400) của hàm gọi → nạp
     * ca hiện tại và ca đích.
     * <p>
     * {@code lockTarget} (duyệt; ADR-0150 mục 18): khóa ca đích {@code FOR SHARE} để sửa / ngừng dùng ca đó ({@code PATCH}
     * #3, {@code FOR NO KEY UPDATE}) xếp hàng với việc đổi ca, đúng thứ tự {@code accounts} → {@code shift_definitions}
     * → phân ca → yêu cầu của phân hộ #5. Mã ca đích đọc không khóa từ {@link RequestOwner} vì cột bất biến; ca chưa từng
     * nạp vào persistence context trước khóa nên {@code is_active} và giờ đọc được là mới. Từ chối truyền {@code false}.
     */
    private LockedRequest lockForDecision(Long shiftRequestId, ShiftRequestStatus target, boolean lockTarget) {
        ShiftAssignmentService.requireBranch(branchScope.current().branchId());

        RequestOwner owner = requests.findOwnerById(shiftRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("yêu cầu", shiftRequestId));
        AssignmentScope scope = assignments.findScopeById(owner.getAssignmentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Yêu cầu " + shiftRequestId + " thiếu phân ca " + owner.getAssignmentId()));
        branchScope.check(scope.getBranchId());

        StaffSummary requester = staffDirectory.findStaffForUpdate(scope.getStaffId())
                .orElseThrow(() -> new IllegalStateException("Phân ca " + owner.getAssignmentId()
                        + " trỏ tới tài khoản không phải nhân viên " + scope.getStaffId()));
        Long targetId = owner.getTargetShiftDefinitionId();
        ShiftDefinition lockedTarget = !lockTarget || targetId == null ? null
                : definitions.findByIdForShare(targetId)
                        .orElseThrow(() -> new IllegalStateException("Yêu cầu " + shiftRequestId + " thiếu ca đích "
                                + targetId));
        ShiftAssignment assignment = assignments.findByIdForUpdate(owner.getAssignmentId())
                .orElseThrow(() -> new IllegalStateException("Yêu cầu " + shiftRequestId + " thiếu phân ca "
                        + owner.getAssignmentId()));
        ShiftRequest request = requests.findByIdForUpdate(shiftRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("yêu cầu", shiftRequestId));

        // Yêu cầu có thể vừa được duyệt / từ chối / rút / hệ thống đóng trong lúc chờ khóa → 409.
        transitions.validateTransition(request.getStatus(), target);
        ShiftDefinition current = definitions.findById(assignment.getShiftDefinitionId())
                .orElseThrow(() -> new IllegalStateException("Phân ca " + assignment.getId() + " thiếu ca làm việc "
                        + assignment.getShiftDefinitionId()));
        ShiftDefinition targetDefinition = lockedTarget != null ? lockedTarget
                : request.getTargetShiftDefinitionId() == null ? null
                : definitions.findById(request.getTargetShiftDefinitionId())
                        .orElseThrow(() -> new IllegalStateException("Yêu cầu " + request.getId()
                                + " thiếu ca đích " + request.getTargetShiftDefinitionId()));
        return new LockedRequest(requester, assignment, request, current, targetDefinition);
    }

    /** Dữ liệu đọc dưới khóa của {@link #lockForDecision}; {@code target} là {@code null} với yêu cầu nghỉ. */
    private record LockedRequest(StaffSummary requester, ShiftAssignment assignment, ShiftRequest request,
            ShiftDefinition current, ShiftDefinition target) {
    }

    /**
     * Ca đích phải đang dùng, khác ca hiện tại và không chồng giờ phân ca <b>khác</b> của người đó trong ngày. Dùng chung
     * cho gửi #9 ({@code BR-NS-04}) và duyệt {@code SWAP} #13 ({@code BR-NS-06}, kiểm lại lúc duyệt); cùng câu thông
     * báo, chỉ khác mã rule. Chạy dưới khóa {@code accounts} của nhân viên: mọi bên ghi phân ca của người đó khóa dòng này
     * trước, nên kết quả kiểm chồng giờ không đổi tới khi commit.
     */
    private void checkTarget(ShiftAssignment assignment, ShiftDefinition current, ShiftDefinition target,
            String ruleId) {
        if (!target.isActive()) {
            throw new BusinessRuleViolationException(ruleId, "Ca đích đã ngừng dùng");
        }
        if (target.getId().equals(current.getId())) {
            throw new BusinessRuleViolationException(ruleId, "Ca đích trùng với ca hiện tại");
        }
        // Phân ca đang đổi không tính: đổi sang ca liền kề hay giao một phần với ca hiện tại là hợp lệ vì nó sẽ được thay.
        List<OverlappingShift> overlaps = assignments.findOverlapping(assignment.getStaffId(),
                assignment.getWorkDate(), target.getStartTime(), target.getEndTime()).stream()
                .filter(overlap -> !overlap.getAssignmentId().equals(assignment.getId()))
                .toList();
        if (!overlaps.isEmpty()) {
            throw new BusinessRuleViolationException(ruleId,
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
        ShiftNotices.submitted(recipients, staff.fullName(), saved.getRequestType(), current.getName(),
                assignment.getWorkDate(), branchNameOf(assignment), saved.getReason()).forEach(notifications::enqueue);
    }

    /**
     * Ghép theo lô: đúng một lần cho mỗi nguồn (người gửi, phân ca, ca), bất kể số dòng. {@code currentShiftName} là tên
     * ca <b>lúc gửi</b> ({@code currentShiftDefinitionId} của yêu cầu, V60), không phải ca đang gắn với phân ca (khác nhau
     * sau khi duyệt {@code SWAP}); phân ca chỉ còn dùng cho {@code workDate}.
     */
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
        rows.forEach(row -> definitionIds.add(row.getCurrentShiftDefinitionId()));
        rows.stream().map(ShiftRequest::getTargetShiftDefinitionId).filter(Objects::nonNull)
                .forEach(definitionIds::add);
        Map<Long, ShiftDefinition> definitionById = new HashMap<>();
        definitions.findAllById(definitionIds).forEach(found -> definitionById.put(found.getId(), found));

        return rows.stream().map(row -> {
            String requesterName = staffNames.get(row.getRequesterId());
            ShiftAssignment assignment = assignmentById.get(row.getAssignmentId());
            ShiftDefinition currentDefinition = definitionById.get(row.getCurrentShiftDefinitionId());
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
